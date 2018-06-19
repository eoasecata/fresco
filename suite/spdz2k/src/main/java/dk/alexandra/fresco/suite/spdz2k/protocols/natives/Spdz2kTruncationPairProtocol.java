package dk.alexandra.fresco.suite.spdz2k.protocols.natives;

import dk.alexandra.fresco.framework.builder.numeric.AdvancedNumeric.TruncationPair;
import dk.alexandra.fresco.framework.network.Network;
import dk.alexandra.fresco.suite.spdz2k.datatypes.CompUInt;
import dk.alexandra.fresco.suite.spdz2k.resource.Spdz2kResourcePool;

public class Spdz2kTruncationPairProtocol<PlainT extends CompUInt<?, ?, PlainT>> extends
    Spdz2kNativeProtocol<TruncationPair, PlainT> {

  private TruncationPair pair;
  private final int d;

  public Spdz2kTruncationPairProtocol(int d) {
    this.d = d;
  }

  @Override
  public EvaluationStatus evaluate(int round, Spdz2kResourcePool<PlainT> resourcePool,
      Network network) {
    pair = resourcePool.getDataSupplier().getNextTruncationPair(d);
    return EvaluationStatus.IS_DONE;
  }

  @Override
  public TruncationPair out() {
    return pair;
  }
}
