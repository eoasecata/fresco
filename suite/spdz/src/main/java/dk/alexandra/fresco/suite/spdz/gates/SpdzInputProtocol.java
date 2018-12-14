package dk.alexandra.fresco.suite.spdz.gates;

import dk.alexandra.fresco.framework.MaliciousException;
import dk.alexandra.fresco.framework.builder.numeric.field.FieldDefinition;
import dk.alexandra.fresco.framework.builder.numeric.field.FieldElement;
import dk.alexandra.fresco.framework.network.Network;
import dk.alexandra.fresco.framework.value.SInt;
import dk.alexandra.fresco.suite.spdz.SpdzResourcePool;
import dk.alexandra.fresco.suite.spdz.datatypes.SpdzInputMask;
import dk.alexandra.fresco.suite.spdz.datatypes.SpdzSInt;
import dk.alexandra.fresco.suite.spdz.storage.SpdzDataSupplier;

public class SpdzInputProtocol extends SpdzNativeProtocol<SInt> {

  private SpdzInputMask inputMask; // is opened by this gate.
  protected FieldElement input;
  private byte[] receivedMaskedValue;
  protected SpdzSInt out;
  private int inputter;
  private byte[] digest;

  public SpdzInputProtocol(FieldElement input, int inputter) {
    this.input = input;
    this.inputter = inputter;
  }

  @Override
  public EvaluationStatus evaluate(int round, SpdzResourcePool spdzResourcePool,
      Network network) {
    int myId = spdzResourcePool.getMyId();
    SpdzDataSupplier dataSupplier = spdzResourcePool.getDataSupplier();
    FieldDefinition definition = spdzResourcePool.getFieldDefinition();
    if (round == 0) {
      this.inputMask = dataSupplier.getNextInputMask(this.inputter);
      if (myId == this.inputter) {
        FieldElement bcValue = this.input.subtract(this.inputMask.getRealValue());
        network.sendToAll(definition.serialize(bcValue));
      }
      return EvaluationStatus.HAS_MORE_ROUNDS;
    } else if (round == 1) {
      this.receivedMaskedValue = network.receive(inputter);
      this.digest = sendBroadcastValidation(
          spdzResourcePool.getMessageDigest(), network,
          receivedMaskedValue);
      return EvaluationStatus.HAS_MORE_ROUNDS;
    } else {
      boolean validated = receiveBroadcastValidation(network, digest);
      if (!validated) {
        throw new MaliciousException("Broadcast digests did not match");
      }
      FieldElement multiply = definition.deserialize(receivedMaskedValue);
      FieldElement maskedValue = dataSupplier.getSecretSharedKey().multiply(multiply);
      SpdzSInt valueMaskedElement = new SpdzSInt(maskedValue, maskedValue);
      this.out = this.inputMask.getMask().add(valueMaskedElement, myId);
      return EvaluationStatus.IS_DONE;
    }
  }

  @Override
  public SpdzSInt out() {
    return out;
  }
}
