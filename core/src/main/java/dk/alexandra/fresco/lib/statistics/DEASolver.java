/*
 * Copyright (c) 2015, 2016 FRESCO (http://github.com/aicis/fresco).
 *
 * This file is part of the FRESCO project.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * FRESCO uses SCAPI - http://crypto.biu.ac.il/SCAPI, Crypto++, Miracl, NTL,
 * and Bouncy Castle. Please see these projects for any further licensing issues.
 *******************************************************************************/
package dk.alexandra.fresco.lib.statistics;

import dk.alexandra.fresco.framework.Application;
import dk.alexandra.fresco.framework.Computation;
import dk.alexandra.fresco.framework.MPCException;
import dk.alexandra.fresco.framework.builder.ProtocolBuilderNumeric.SequentialNumericBuilder;
import dk.alexandra.fresco.framework.util.Pair;
import dk.alexandra.fresco.framework.value.SInt;
import dk.alexandra.fresco.lib.lp.LPSolver;
import dk.alexandra.fresco.lib.lp.LPSolver.PivotRule;
import dk.alexandra.fresco.lib.lp.LPTableau;
import dk.alexandra.fresco.lib.lp.Matrix;
import dk.alexandra.fresco.lib.lp.OptimalValue;
import dk.alexandra.fresco.lib.lp.SimpleLPPrefix;
import dk.alexandra.fresco.lib.statistics.DEASolver.DEAResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NativeProtocol for solving DEA problems.
 *
 * Given a dataset (two matrices of inputs and outputs) and a number of query
 * vectors, the protocol will compute how well the query vectors perform
 * compared to the dataset.
 *
 * The result/score of the computation must be converted to a double using Gauss
 * reduction to be meaningful. See the DEASolverTests for an example.
 */
public class DEASolver implements Application<List<DEAResult>, SequentialNumericBuilder> {


  private final List<List<SInt>> targetInputs, targetOutputs;
  private final List<List<SInt>> inputDataSet, outputDataSet;

  private final AnalysisType type;
  private final PivotRule pivotRule;


  /**
   * Construct a DEA problem for the solver to solve. The problem consists of
   * 4 matrixes: 2 basis input/output matrices containing the dataset which
   * the queries will be measured against
   *
   * 2 query input/output matrices containing the data to be evaluated.
   *
   * @param type The type of analysis to do
   * @param inputValues Matrix of query input values
   * @param outputValues Matrix of query output values
   * @param setInput Matrix containing the basis input
   * @param setOutput Matrix containing the basis output
   */
  public DEASolver(AnalysisType type, List<List<SInt>> inputValues,
      List<List<SInt>> outputValues,
      List<List<SInt>> setInput,
      List<List<SInt>> setOutput) throws MPCException {
    this(PivotRule.DANZIG, type, inputValues, outputValues, setInput, setOutput);
  }

  /**
   * Construct a DEA problem for the solver to solve. The problem consists of
   * 4 matrixes: 2 basis input/output matrices containing the dataset which
   * the queries will be measured against
   *
   * 2 query input/output matrices containing the data to be evaluated.
   *
   * @param pivotRule the pivot rule to use in LP solver
   * @param type The type of analysis to do
   * @param inputValues Matrix of query input values
   * @param outputValues Matrix of query output values
   * @param setInput Matrix containing the basis input
   * @param setOutput Matrix containing the basis output
   */
  public DEASolver(
      PivotRule pivotRule,
      AnalysisType type,
      List<List<SInt>> inputValues,
      List<List<SInt>> outputValues,
      List<List<SInt>> setInput,
      List<List<SInt>> setOutput) throws MPCException {
    this.pivotRule = pivotRule;
    this.type = type;
    this.targetInputs = inputValues;
    this.targetOutputs = outputValues;
    this.inputDataSet = setInput;
    this.outputDataSet = setOutput;
    if (!consistencyCheck()) {
      throw new MPCException("Inconsistent dataset / query data");
    }
  }

  /**
   * Verify that the input is consistent
   *
   * @return If the input is consistent.
   */
  private boolean consistencyCheck() {

    int inputVariables = inputDataSet.get(0).size();
    int outputVariables = outputDataSet.get(0).size();
    if (inputDataSet.size() != outputDataSet.size()) {
      return false;
    }
    if (targetInputs.size() != targetOutputs.size()) {
      return false;
    }
    for (List<SInt> x : targetInputs) {
      if (x.size() != inputVariables) {
        return false;
      }
    }
    for (List<SInt> x : inputDataSet) {
      if (x.size() != inputVariables) {
        return false;
      }
    }
    for (List<SInt> x : targetOutputs) {
      if (x.size() != outputVariables) {
        return false;
      }
    }
    for (List<SInt> x : outputDataSet) {
      if (x.size() != outputVariables) {
        return false;
      }
    }
    return true;
  }

  @Override
  public Computation<List<DEAResult>> prepareApplication(SequentialNumericBuilder builder) {
    List<Computation<SimpleLPPrefix>> prefixes = getPrefixWithSecretSharedValues(
        builder);
    return builder.par((par) -> {

      List<Computation<Pair<List<Computation<SInt>>, Computation<SInt>>>> result =
          new ArrayList<>(targetInputs.size());
      for (int i = 0; i < targetInputs.size(); i++) {

        SimpleLPPrefix prefix = prefixes.get(i).out();
        Computation<SInt> pivot = prefix.getPivot();
        LPTableau tableau = prefix.getTableau();
        Matrix<Computation<SInt>> update = prefix.getUpdateMatrix();
        List<Computation<SInt>> initialBasis = prefix.getBasis();

        result.add(
            par.createSequentialSub((subSeq) ->
                subSeq.seq((solverSec) -> {
                  LPSolver lpSolver = new LPSolver(
                      pivotRule, tableau, update, pivot, initialBasis);
                  return lpSolver.build(solverSec);

                }).seq((lpOutput, optSec) ->
                    Pair.lazy(
                        lpOutput.basis,
                        new OptimalValue(lpOutput.updateMatrix, lpOutput.tableau, lpOutput.pivot)
                            .build(optSec)
                    )
                ))
        );
      }
      return () -> result;
    }).seq((result, seq) -> {
      List<DEAResult> convertedResult = result.stream().map(DEAResult::new)
          .collect(Collectors.toList());
      return () -> convertedResult;
    });
  }

  private List<Computation<SimpleLPPrefix>> getPrefixWithSecretSharedValues(
      SequentialNumericBuilder builder) {
    int dataSetSize = this.inputDataSet.size();

    int noOfSolvers = this.targetInputs.size();
    List<Computation<SimpleLPPrefix>> prefixes = new ArrayList<>(noOfSolvers);

    int lpInputs = this.inputDataSet.get(0).size();
    int lpOutputs = this.outputDataSet.get(0).size();
    SInt[][] basisInputs = new SInt[lpInputs][dataSetSize];
    SInt[][] basisOutputs = new SInt[lpOutputs][dataSetSize];

    for (int i = 0; i < dataSetSize; i++) {
      for (int j = 0; j < inputDataSet.get(i).size(); j++) {
        List<SInt> current = inputDataSet.get(i);
        basisInputs[j][i] = current.get(j);
      }
      for (int j = 0; j < outputDataSet.get(i).size(); j++) {
        List<SInt> current = outputDataSet.get(i);
        basisOutputs[j][i] = current.get(j);
      }
    }
    for (int i = 0; i < noOfSolvers; i++) {
      if (type == AnalysisType.INPUT_EFFICIENCY) {
        prefixes.add(DEAInputEfficiencyPrefixBuilder.build(
            Arrays.asList(basisInputs), Arrays.asList(basisOutputs),
            targetInputs.get(i), targetOutputs.get(i),
            builder
        ));
      } else {
        prefixes.add(DEAPrefixBuilderMaximize.build(
            Arrays.asList(basisInputs), Arrays.asList(basisOutputs),
            targetInputs.get(i), targetOutputs.get(i),
            builder
        ));
      }
    }
    return prefixes;
  }

  public enum AnalysisType {INPUT_EFFICIENCY, OUTPUT_EFFICIENCY}

  public static class DEAResult {

    public final List<SInt> basis;
    public final SInt optimal;

    private DEAResult(Computation<Pair<List<Computation<SInt>>, Computation<SInt>>> output) {
      Pair<List<Computation<SInt>>, Computation<SInt>> out = output.out();
      this.basis = out.getFirst().stream().map(Computation::out).collect(Collectors.toList());
      this.optimal = out.getSecond().out();
    }
  }
}