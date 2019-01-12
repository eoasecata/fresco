package dk.alexandra.fresco.suite.spdz2k.protocols.computations;

import dk.alexandra.fresco.commitment.HashBasedCommitment;
import dk.alexandra.fresco.framework.DRes;
import dk.alexandra.fresco.framework.MaliciousException;
import dk.alexandra.fresco.framework.builder.Computation;
import dk.alexandra.fresco.framework.builder.numeric.ProtocolBuilderNumeric;
import dk.alexandra.fresco.framework.network.serializers.ByteSerializer;
import dk.alexandra.fresco.framework.util.Drbg;
import dk.alexandra.fresco.framework.util.Pair;
import dk.alexandra.fresco.suite.spdz2k.datatypes.CompUInt;
import dk.alexandra.fresco.suite.spdz2k.datatypes.CompUIntConverter;
import dk.alexandra.fresco.suite.spdz2k.datatypes.CompUIntFactory;
import dk.alexandra.fresco.suite.spdz2k.datatypes.Spdz2kSIntArithmetic;
import dk.alexandra.fresco.suite.spdz2k.datatypes.UInt;
import dk.alexandra.fresco.suite.spdz2k.resource.Spdz2kResourcePool;
import dk.alexandra.fresco.suite.spdz2k.resource.storage.Spdz2kDataSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Computation for performing batched mac-check on all currently opened, unchecked values.
 */
public class MacCheckComputationSpdz2k<
    HighT extends UInt<HighT>,
    LowT extends UInt<LowT>,
    PlainT extends CompUInt<HighT, LowT, PlainT>>
    implements Computation<Void, ProtocolBuilderNumeric> {

  private final CompUIntConverter<HighT, LowT, PlainT> converter;
  private final ByteSerializer<PlainT> serializer;
  private final Spdz2kDataSupplier<PlainT> supplier;
  private final List<Spdz2kSIntArithmetic<PlainT>> authenticatedElements;
  private final List<PlainT> openValues;
  private final List<PlainT> randomCoefficients;
  private ByteSerializer<HashBasedCommitment> commitmentSerializer;
  private final int noOfParties;
  private final Drbg localDrbg;
  private long then;

  /**
   * Creates new {@link MacCheckComputationSpdz2k}.
   *
   * @param toCheck authenticated elements and open values that must be checked
   * @param resourcePool resources for running Spdz2k
   * @param converter utility class for converting between {@link HighT} and {@link PlainT}, {@link
   * LowT} and {@link PlainT}
   */
  public MacCheckComputationSpdz2k(Pair<List<Spdz2kSIntArithmetic<PlainT>>, List<PlainT>> toCheck,
      Spdz2kResourcePool<PlainT> resourcePool,
      CompUIntConverter<HighT, LowT, PlainT> converter) {
    this.then = System.currentTimeMillis();
    this.authenticatedElements = toCheck.getFirst();
    this.openValues = toCheck.getSecond();
    this.converter = converter;
    this.serializer = resourcePool.getPlainSerializer();
    this.supplier = resourcePool.getDataSupplier();
    this.randomCoefficients = sampleCoefficients(
        resourcePool.getRandomGenerator(),
        resourcePool.getFactory(),
        authenticatedElements.size());
//    System.out.println("Done sampling " + (System.currentTimeMillis() - then));
//    then = System.currentTimeMillis();
//    if (resourcePool.getMyId() == 1) {
//      System.out.println(authenticatedElements.size());
//    }
    this.commitmentSerializer = resourcePool.getCommitmentSerializer();
    this.noOfParties = resourcePool.getNoOfParties();
    this.localDrbg = resourcePool.getLocalRandomGenerator();
  }

  @Override
  public DRes<Void> buildComputation(ProtocolBuilderNumeric builder) {
    PlainT macKeyShare = supplier.getSecretSharedKey();
    long thenthen = System.currentTimeMillis();
    PlainT y = UInt.innerProduct(openValues, randomCoefficients);
//    System.out.println("Inner product " + (System.currentTimeMillis() - thenthen));
    Spdz2kSIntArithmetic<PlainT> r = supplier.getNextRandomElementShare();
    return builder
        .seq(seq -> {
          if (noOfParties > 2) {
            List<byte[]> sharesLowBits = authenticatedElements.stream()
                .map(element -> element.getShare().getLeastSignificant().toByteArray())
                .collect(Collectors.toList());
            return new BroadcastComputation<ProtocolBuilderNumeric>(sharesLowBits, true)
                .buildComputation(seq);
          } else {
            return null;
          }
        })
        .seq((seq, ignored) -> computePValues(seq, authenticatedElements, r))
        .seq((seq, broadcastPjs) -> computeZValues(seq, authenticatedElements, macKeyShare, y, r,
            broadcastPjs))
        .seq((seq, commitZjs) -> {
          List<PlainT> elements = serializer.deserializeList(commitZjs);
          PlainT sum = UInt.sum(elements);
          if (!sum.isZero()) {
            throw new MaliciousException("Mac check failed");
          }
          authenticatedElements.clear();
          openValues.clear();
//          System.out.println("Done mac-check " + (System.currentTimeMillis() - then));
          then = System.currentTimeMillis();
          return null;
        });
  }

  private HighT computePj(PlainT originalShare, PlainT randomCoefficient) {
    HighT overflow = computeDifference(originalShare);
    HighT randomCoefficientHigh = randomCoefficient.getLeastSignificantAsHigh();
    return overflow.multiply(randomCoefficientHigh);
  }

  private DRes<List<byte[]>> computePValues(ProtocolBuilderNumeric builder,
      List<Spdz2kSIntArithmetic<PlainT>> authenticatedElements,
      Spdz2kSIntArithmetic<PlainT> r) {
    HighT pj = computePj(authenticatedElements.get(0).getShare(), randomCoefficients.get(0));
    for (int i = 1; i < authenticatedElements.size(); i++) {
      PlainT share = authenticatedElements.get(i).getShare();
      PlainT randomCoefficient = randomCoefficients.get(i);
      pj = pj.add(computePj(share, randomCoefficient));
    }
    final HighT add = pj.add(r.getShare().getLeastSignificantAsHigh());
    byte[] pjBytes = add.toByteArray();
    return new BroadcastComputation<ProtocolBuilderNumeric>(pjBytes, noOfParties > 2)
        .buildComputation(builder);
  }

  private DRes<List<byte[]>> computeZValues(ProtocolBuilderNumeric builder,
      List<Spdz2kSIntArithmetic<PlainT>> authenticatedElements,
      PlainT macKeyShare, PlainT y, Spdz2kSIntArithmetic<PlainT> r,
      List<byte[]> broadcastPjs) {
    List<PlainT> pjList = serializer.deserializeList(broadcastPjs);
    HighT pLow = UInt.sum(
        pjList.stream().map(PlainT::getLeastSignificantAsHigh).collect(Collectors.toList()));
    PlainT p = converter.createFromHigh(pLow);
    List<PlainT> macShares = authenticatedElements.stream()
        .map(Spdz2kSIntArithmetic::getMacShare)
        .collect(Collectors.toList());
    PlainT mj = UInt.innerProduct(macShares, randomCoefficients);
    PlainT zj = macKeyShare.multiply(y)
        .subtract(mj)
        .subtract(p.multiply(macKeyShare).shiftLowIntoHigh())
        .add(r.getMacShare().shiftLowIntoHigh());
    return new CommitmentComputationSpdz2k(commitmentSerializer, serializer.serialize(zj),
        noOfParties, localDrbg).buildComputation(builder);
  }

  /**
   * Samples random coefficients for mac-check using joint source of randomness.
   */
  private List<PlainT> sampleCoefficients(Drbg drbg, CompUIntFactory<PlainT> factory,
      int numCoefficients) {
    List<PlainT> randomCoefficients = new ArrayList<>(numCoefficients);
    for (int i = 0; i < numCoefficients; i++) {
      byte[] bytes = new byte[factory.getHighBitLength() / Byte.SIZE];
      drbg.nextBytes(bytes);
      randomCoefficients.add(factory.createFromBytes(bytes));
    }
    return randomCoefficients;
  }

  /**
   * For the input, where low denotes the value representing the k lower bits of v, and s is the
   * number of upper bits, compute ((low - s) % 2^{k + s} >> k) % 2^s.
   */
  private HighT computeDifference(PlainT value) {
    PlainT low = converter.createFromLow(value.getLeastSignificant());
    return low.subtract(value).getMostSignificant();
  }

}