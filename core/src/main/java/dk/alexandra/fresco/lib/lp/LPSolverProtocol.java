/*
 * Copyright (c) 2015, 2016 FRESCO (http://github.com/aicis/fresco).
 *
 * This file is part of the FRESCO project.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * FRESCO uses SCAPI - http://crypto.biu.ac.il/SCAPI, Crypto++, Miracl, NTL, and Bouncy Castle.
 * Please see these projects for any further licensing issues.
 *******************************************************************************/
package dk.alexandra.fresco.lib.lp;

import dk.alexandra.fresco.framework.MPCException;
import dk.alexandra.fresco.framework.NativeProtocol;
import dk.alexandra.fresco.framework.ProtocolCollection;
import dk.alexandra.fresco.framework.ProtocolProducer;
import dk.alexandra.fresco.framework.Reporter;
import dk.alexandra.fresco.framework.value.OInt;
import dk.alexandra.fresco.framework.value.SInt;
import dk.alexandra.fresco.lib.field.integer.BasicNumericFactory;
import dk.alexandra.fresco.lib.helper.AbstractRoundBasedProtocol;
import dk.alexandra.fresco.lib.helper.CopyProtocol;
import dk.alexandra.fresco.lib.helper.ParallelProtocolProducer;
import dk.alexandra.fresco.lib.helper.sequential.SequentialProtocolProducer;
import java.math.BigInteger;

/**
 * A protocol for solving LP problems using the Simplex method.
 *
 * <p>
 * We basically use the method of
 * <a href="http://fc09.ifca.ai/papers/69_Solving_linear_programs.pdf">Toft 2009</a>.
 *
 * We optimize this protocol by using the so called <i>Revised Simplex</i> method.
 * I.e., instead of updating the tableau it self, we keep track of much smaller
 * update matrix representing changes to the initial tableau. Do this only requires
 * multiplication with a small sparse matrix, which can be done more efficiently
 * than general matrix multiplication.
 * </p>
 */
public class LPSolverProtocol implements ProtocolProducer {

  private final LPTableau tableau;
  private final Matrix<SInt> updateMatrix;
  private final SInt zero;
  private int identityHashCode;

  private enum STATE {
    PHASE1, PHASE2, TERMINATED
  }

  private STATE state;
  private LPFactory lpFactory;
  private BasicNumericFactory bnFactory;
  private ProtocolProducer pp;
  private OInt terminationOut;
  private Matrix<SInt> newUpdateMatrix;
  private final SInt prevPivot;
  private SInt pivot;
  private SInt[] enteringIndex;

  private final SInt[] basis;
  private static final int DEFAULT_BASIS_VALUE = 0;
  private final OInt[] enumeratedVariables; // [1,2,3,...]

  private int iterations = 0;
  private final int noVariables;
  private final int noConstraints;

  public LPSolverProtocol(LPTableau tableau, Matrix<SInt> updateMatrix, SInt pivot, SInt[] basis,
      LPFactory lpFactory, BasicNumericFactory bnFactory) {
    if (checkDimensions(tableau, updateMatrix)) {
      this.tableau = tableau;
      this.updateMatrix = updateMatrix;
      this.prevPivot = pivot;
      this.pp = null;
      this.lpFactory = lpFactory;
      this.bnFactory = bnFactory;
      this.zero = bnFactory.getSInt(0);
      this.state = STATE.PHASE1;
      this.iterations = 0;
      this.basis = basis;
      for (int i = 0; i < this.basis.length; i++) {
        this.basis[i] = bnFactory.getSInt(DEFAULT_BASIS_VALUE);
      }
      this.noVariables = tableau.getC().getWidth();
      this.noConstraints = tableau.getC().getHeight();
      this.enumeratedVariables = new OInt[noVariables];
      for (int i = 1; i <= enumeratedVariables.length; i++) {
        this.enumeratedVariables[i - 1] = this.bnFactory.getOInt(BigInteger.valueOf(i));
      }
    } else {
      throw new MPCException("Dimensions of inputs does not match");
    }
    identityHashCode = System.identityHashCode(this);
  }

  private boolean checkDimensions(LPTableau tableau, Matrix<SInt> updateMatrix) {
    int updateHeight = updateMatrix.getHeight();
    int updateWidth = updateMatrix.getWidth();
    int tableauHeight = tableau.getC().getHeight() + 1;
    return (updateHeight == updateWidth && updateHeight == tableauHeight);

  }

  @Override
  public void getNextProtocols(ProtocolCollection protocolCollection) {
    if (pp == null) {
      if (state == STATE.PHASE1) {
        iterations++;
        Reporter.info("LP Iterations=" + iterations + " solving " +
            identityHashCode);
        pp = phaseOneProtocol();
      } else { // if (state == STATE.PHASE2)
        boolean terminated = terminationOut.getValue().equals(BigInteger.ONE);
        if (!terminated) {
          pp = phaseTwoProtocol();
        } else {
          state = STATE.TERMINATED;
          pp = null;
          return;
        }
      }
    }
    if (pp.hasNextProtocols()) {
      pp.getNextProtocols(protocolCollection);
    } else {
      switch (state) {
        case PHASE1:
          pp = null;
          state = STATE.PHASE2;
          break;
        case PHASE2:
          pp = null;
          state = STATE.PHASE1;
          break;
        case TERMINATED:
          pp = null;
          break;
        default:
          break;
      }
    }
  }

  /**
   * Creates a ProtocolProducer computing the second half of a simplex iteration.
   *
   * <p>
   * This finds the exiting variable index by finding the most constraining
   * constraint on the entering variable. Having the exiting variable index also
   * gives us the pivot. Having the entering and exiting indices and the pivot
   * allows us to compute the new update matrix for the next iteration.
   *
   * Additionally, having the entering and exiting variables we can update
   * the basis of the current solution.
   * </p>
   */
  private ProtocolProducer phaseTwoProtocol() {
    // Phase 2 - Finding the exiting variable and updating the tableau
    return new AbstractRoundBasedProtocol() {
      int round = 0;
      SInt[] exitingIndex, updateColumn;
      SInt[][] newUpdate;

      @Override
      public ProtocolProducer nextProtocolProducer() {
        switch (round) {
          case 0:
            exitingIndex = new SInt[noConstraints];
            for (int i = 0; i < exitingIndex.length; i++) {
              exitingIndex[i] = bnFactory.getSInt();
            }
            updateColumn = new SInt[noConstraints + 1];
            for (int i = 0; i < updateColumn.length; i++) {
              updateColumn[i] = bnFactory.getSInt();
            }
            pivot = bnFactory.getSInt();
            ProtocolProducer exitingIndexProducer = lpFactory.getExitingVariableProtocol(tableau,
                updateMatrix, enteringIndex, exitingIndex, updateColumn, pivot);
            round++;
            return exitingIndexProducer;
          case 1:
            // Update Basis
            SequentialProtocolProducer updateBasisProducer = new SequentialProtocolProducer();
            ParallelProtocolProducer par = new ParallelProtocolProducer();
            SInt ent = bnFactory.getSInt();
            ProtocolProducer ipp = lpFactory.getInnerProductProtocol(enteringIndex,
                enumeratedVariables, ent);
            updateBasisProducer.append(ipp);
            for (int i = 0; i < noConstraints; i++) {
              par.append(
                  lpFactory.getConditionalSelectProtocol(exitingIndex[i], ent,
                      basis[i], basis[i]));
            }
            updateBasisProducer.append(par);
            // Update the update matrix
            newUpdate = new SInt[noConstraints + 1][noConstraints + 1];
            for (int i = 0; i < newUpdate.length; i++) {
              for (int j = 0; j < newUpdate[i].length; j++) {
                newUpdate[i][j] = bnFactory.getSInt();
              }
            }
            newUpdateMatrix = new Matrix<>(newUpdate);
            ProtocolProducer updateMatrixProducer = lpFactory.getUpdateMatrixProtocol(updateMatrix,
                exitingIndex, updateColumn, pivot, prevPivot, newUpdateMatrix);
            round++;
            return new ParallelProtocolProducer(updateBasisProducer, updateMatrixProducer);
          case 2:
            // Copy the resulting new update matrix to overwrite the current
            ParallelProtocolProducer parCopy = new ParallelProtocolProducer();
            for (int i = 0; i < newUpdate.length; i++) {
              for (int j = 0; j < newUpdate[i].length; j++) {
                CopyProtocol<SInt> copy = lpFactory.getCopyProtocol(
                    newUpdateMatrix.getElement(i, j), updateMatrix.getElement(i, j));
                parCopy.append(copy);
              }
            }
            CopyProtocol<SInt> copy = lpFactory.getCopyProtocol(pivot, prevPivot);
            parCopy.append(copy);
            round++;
            return parCopy;
          default:
            break;
        }
        return null;
      }
    };
  }

  /**
   * Creates a ProtocolProducer to compute the first half of a simplex iteration.
   * <p>
   * This finds the variable to enter the basis, based on the pivot rule of most
   * negative entry in the <i>F</i> vector. Also tests if no negative entry in
   * the <i>F</i> vector is present. If this is the case we should terminate
   * the simplex method.
   * </p>
   *
   * @return a protocol producer for the first half of a simplex iteration
   */
  private ProtocolProducer phaseOneProtocol() {
    terminationOut = bnFactory.getOInt();
    enteringIndex = new SInt[noVariables];
    for (int i = 0; i < noVariables; i++) {
      enteringIndex[i] = bnFactory.getSInt();
    }
    SInt minimum = bnFactory.getSInt();
    // Compute potential entering variable index and corresponding value of
    // entry in F
    ProtocolProducer enteringProducer =
        lpFactory.getEnteringVariableProtocol(tableau, updateMatrix, enteringIndex, minimum);
    // Check if the entry in F is non-negative
    SInt positive = bnFactory.getSInt();
    ProtocolProducer comp = lpFactory.getComparisonProtocol(zero, minimum, positive, true);
    SequentialProtocolProducer phaseOne = new SequentialProtocolProducer(enteringProducer, comp);
    NativeProtocol output = bnFactory.getOpenProtocol(positive, terminationOut);
    phaseOne.append(output);
    return phaseOne;
  }

  /**
   * Creates a ProtocolProducer to compute the first half of a simplex iteration.
   * <p>
   * This finds the variable to enter the basis, based on Blands the pivot rule
   * using the first  negative entry in the <i>F</i> vector. Also tests if no
   * negative entry in the <i>F</i> vector is present. If this is the case we
   * should terminate the simplex method.
   * </p>
   *
   * @return a protocol producer for the first half of a simplex iteration
   */
  @SuppressWarnings("unused")
  private ProtocolProducer blandPhaseOneProtocol() {
    terminationOut = bnFactory.getOInt();
    // Phase 1 - Finding the entering variable and outputting
    // whether or not the corresponding F value is positive (a positive
    // value indicating termination)
    enteringIndex = new SInt[noVariables];
    for (int i = 0; i < noVariables; i++) {
      enteringIndex[i] = bnFactory.getSInt();
    }

    SInt first = bnFactory.getSInt();
    ProtocolProducer blandEnter = new BlandEnteringVariableProtocol(tableau, updateMatrix,
        enteringIndex, first, lpFactory, bnFactory);

    NativeProtocol output = bnFactory.getOpenProtocol(first, terminationOut);
    return new SequentialProtocolProducer(blandEnter, output);
  }


  @Override
  public boolean hasNextProtocols() {
    return (state != STATE.TERMINATED);
  }

  public static class Builder {

    LPTableau tableau;
    Matrix<SInt> updateMatrix;
    SInt pivot;
    SInt[] basis;
    LPFactory lpFactory;
    BasicNumericFactory bnFactory;

    public Builder() {
      this.tableau = null;
      this.updateMatrix = null;
      this.pivot = null;
      this.basis = null;
      this.lpFactory = null;
      this.bnFactory = null;
    }

    public Builder tableau(LPTableau tableau) {
      this.tableau = tableau;
      return this;
    }

    public Builder updateMatrix(Matrix<SInt> updateMatrix) {
      this.updateMatrix = updateMatrix;
      return this;
    }

    public Builder pivot(SInt pivot) {
      this.pivot = pivot;
      return this;
    }

    public Builder basis(SInt[] basis) {
      this.basis = basis;
      return this;
    }

    public Builder lpFactory(LPFactory lpf) {
      this.lpFactory = lpf;
      return this;
    }

    public Builder bnFactory(BasicNumericFactory bnf) {
      this.bnFactory = bnf;
      return this;
    }

    public <T extends BasicNumericFactory & LPFactory> Builder omniProvider(T factory) {
      this.lpFactory = factory;
      this.bnFactory = factory;
      return this;
    }

    public LPSolverProtocol build() {
      if (this.tableau != null && this.updateMatrix != null && this.pivot != null
          && this.basis != null && this.lpFactory != null && this.bnFactory != null) {
        return new LPSolverProtocol(tableau, updateMatrix, pivot, basis, lpFactory, bnFactory);
      } else {
        throw new IllegalStateException("Not ready to build. Some values where not set.");
      }
    }
  }
}
