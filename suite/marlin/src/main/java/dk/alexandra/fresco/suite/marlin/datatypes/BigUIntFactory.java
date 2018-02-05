package dk.alexandra.fresco.suite.marlin.datatypes;

import java.math.BigInteger;

public interface BigUIntFactory<T extends BigUInt<T>> {

  /**
   * Creates new {@link T} from a raw array of bytes.
   */
  T createFromBytes(byte[] bytes);

  /**
   * Creates new {@link T} from a {@link BigInteger}.
   */
  default T createFromBigInteger(BigInteger value) {
    return createFromBytes(value.toByteArray());
  }

  /**
   * Creates random {@link T}.
   */
  T createRandom();

  /**
   * Creates element whose value is zero.
   */
  default T createZero() {
    return createFromBytes(new byte[getBitLength() / 8]);
  }

  int getBitLength();

}
