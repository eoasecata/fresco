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
package dk.alexandra.fresco.lib.helper.builder;

import dk.alexandra.fresco.framework.Computation;
import dk.alexandra.fresco.framework.ProtocolProducer;
import dk.alexandra.fresco.framework.value.SInt;
import dk.alexandra.fresco.lib.field.integer.BasicNumericFactory;
import dk.alexandra.fresco.lib.helper.CopyProtocol;

public class NumericProtocolBuilder extends AbstractProtocolBuilder {

  private BasicNumericFactory bnf;

  public NumericProtocolBuilder(BasicNumericFactory bnp) {
    this.bnf = bnp;
  }

  /**
   * Get a new SInt.
   *
   * @param value the value to fill the SInt with
   * @return a SInt.
   */
  public SInt getSInt(int value) {
    return bnf.getSInt(value);
  }

  /**
   * Get a new SInt.
   *
   * @return a SInt.
   */
  public SInt getSInt() {
    return bnf.getSInt();
  }

  public SInt known(int value) {
    SInt sValue = bnf.getSInt();
    Computation loader = bnf.getSInt(value, sValue);
    append(loader);
    return sValue;
  }

  /**
   * Adds to SInts
   *
   * @param left the lefthand input
   * @param right the righthand input
   * @return an SInt representing the result of the addition
   */
  public SInt add(SInt left, SInt right) {
    SInt out = bnf.getSInt();
    append(bnf.getAddProtocol(left, right, out));
    return out;
  }

  /**
   * Multiplies two SInts
   *
   * @param left the lefthand input
   * @param right the righthand input
   * @return an SInt representing the result of the multiplication
   */
  public SInt mult(SInt left, SInt right) {
    SInt out = bnf.getSInt();
    append(bnf.getMultProtocol(left, right, out));
    return out;
  }


  /**
   * Subtracts the righthand SInt from the lefthand SInt.
   *
   * @param left the lefthand input
   * @param right the righthand input
   * @return an SInt representing the result of the subtraction.
   */
  public SInt sub(SInt left, SInt right) {
    SInt out = bnf.getSInt();
    append(bnf.getSubtractProtocol(left, right, out));
    return out;
  }

  /**
   * Copies the value of one SInt into an other SInt.
   *
   * Note: this uses the generic CopyProtocol implementation, it is not clear
   * if this is safe for all protocol suites.
   *
   * @param to the SInt to copy to
   * @param from the SInt to copy from
   */
  public void copy(SInt to, SInt from) {
    append(new CopyProtocol<>(from, to));
  }

  @Override
  public void addProtocolProducer(ProtocolProducer gp) {
    append(gp);
  }
}
