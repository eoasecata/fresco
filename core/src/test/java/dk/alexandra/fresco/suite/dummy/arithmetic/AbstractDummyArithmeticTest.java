package dk.alexandra.fresco.suite.dummy.arithmetic;

import dk.alexandra.fresco.framework.ProtocolEvaluator;
import dk.alexandra.fresco.framework.TestThreadRunner;
import dk.alexandra.fresco.framework.builder.numeric.ProtocolBuilderNumeric;
import dk.alexandra.fresco.framework.builder.numeric.field.FieldDefinition;
import dk.alexandra.fresco.framework.builder.numeric.field.MersennePrimeFieldDefinition;
import dk.alexandra.fresco.framework.configuration.NetworkConfiguration;
import dk.alexandra.fresco.framework.configuration.NetworkUtil;
import dk.alexandra.fresco.framework.network.Network;
import dk.alexandra.fresco.framework.network.socket.SocketNetwork;
import dk.alexandra.fresco.framework.sce.SecureComputationEngine;
import dk.alexandra.fresco.framework.sce.SecureComputationEngineImpl;
import dk.alexandra.fresco.framework.sce.evaluator.BatchEvaluationStrategy;
import dk.alexandra.fresco.framework.sce.evaluator.BatchedProtocolEvaluator;
import dk.alexandra.fresco.framework.sce.evaluator.EvaluationStrategy;
import dk.alexandra.fresco.logging.BatchEvaluationLoggingDecorator;
import dk.alexandra.fresco.logging.DefaultPerformancePrinter;
import dk.alexandra.fresco.logging.EvaluatorLoggingDecorator;
import dk.alexandra.fresco.logging.NetworkLoggingDecorator;
import dk.alexandra.fresco.logging.NumericSuiteLogging;
import dk.alexandra.fresco.logging.PerformanceLogger;
import dk.alexandra.fresco.logging.PerformanceLoggerCountingAggregate;
import dk.alexandra.fresco.logging.PerformancePrinter;
import dk.alexandra.fresco.suite.ProtocolSuiteNumeric;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract class which handles a lot of boiler plate testing code. This makes running a single test
 * using different parameters quite easy.
 */
public abstract class AbstractDummyArithmeticTest {

  protected Map<Integer, PerformanceLogger> performanceLoggers = new HashMap<>();
  protected static final FieldDefinition DEFAULT_FIELD =
      MersennePrimeFieldDefinition.find(512);
  protected static final int DEFAULT_MAX_BIT_LENGTH = 200;
  protected static final int DEFAULT_FIXED_POINT_PRECISION = 16;
  protected static final int DEFAULT_PARTIES = 1;
  protected static final EvaluationStrategy DEFAULT_EVALUATION_STRATEGY
      = EvaluationStrategy.SEQUENTIAL_BATCHED;
  protected static final boolean DEFAULT_PERFORMANCE_LOGGING = false;

  /**
   * Runs test using the {@link TestParameters} class to set parameters.
   *
   * @param f the test thread factory
   * @param p the parameters for the test
   */
  protected void runTest(
      TestThreadRunner.TestThreadFactory<DummyArithmeticResourcePool, ProtocolBuilderNumeric> f,
      TestParameters p) {
    runTest(f,
        p.evaluationStrategy,
        p.numParties,
        p.modulus,
        p.maxBitLength,
        p.fixedPointPrecesion,
        p.performanceLogging);
  }

  /**
   * Runs test with default modulus, max bit length, and no performance logging. i.e. standard test
   * setup.
   */
  protected void runTest(
      TestThreadRunner.TestThreadFactory<DummyArithmeticResourcePool, ProtocolBuilderNumeric> f,
      EvaluationStrategy evalStrategy, int noOfParties) {
    runTest(f, evalStrategy, noOfParties, DEFAULT_FIELD, DEFAULT_MAX_BIT_LENGTH,
        DEFAULT_FIXED_POINT_PRECISION, DEFAULT_PERFORMANCE_LOGGING);
  }

  private void runTest(
      TestThreadRunner.TestThreadFactory<DummyArithmeticResourcePool, ProtocolBuilderNumeric> f,
      EvaluationStrategy evalStrategy, int noOfParties, FieldDefinition fieldDefinition,
      int maxBitLength,
      int fixedPointPrecision, boolean logPerformance) {
    List<Integer> ports = new ArrayList<>(noOfParties);
    for (int i = 1; i <= noOfParties; i++) {
      ports.add(9000 + i * (noOfParties - 1));
    }

    Map<Integer, NetworkConfiguration> netConf =
        NetworkUtil.getNetworkConfigurations(ports);
    Map<Integer,
        TestThreadRunner.TestThreadConfiguration<
            DummyArithmeticResourcePool,
            ProtocolBuilderNumeric>
        > conf = new HashMap<>();
    for (int playerId : netConf.keySet()) {
      PerformanceLoggerCountingAggregate aggregate = new PerformanceLoggerCountingAggregate();
      ProtocolSuiteNumeric<DummyArithmeticResourcePool> ps = new DummyArithmeticProtocolSuite(
          fieldDefinition, maxBitLength, fixedPointPrecision);
      if (logPerformance) {
        ps = new NumericSuiteLogging<>(ps);
        aggregate.add((PerformanceLogger) ps);
      }

      BatchEvaluationStrategy<DummyArithmeticResourcePool> batchEvaluationStrategy =
          evalStrategy.getStrategy();
      if (logPerformance) {
        batchEvaluationStrategy = new BatchEvaluationLoggingDecorator<>(batchEvaluationStrategy);
        aggregate.add((PerformanceLogger) batchEvaluationStrategy);
      }
      ProtocolEvaluator<DummyArithmeticResourcePool> evaluator =
          new BatchedProtocolEvaluator<>(batchEvaluationStrategy, ps);
      if (logPerformance) {
        evaluator = new EvaluatorLoggingDecorator<>(evaluator);
        aggregate.add((PerformanceLogger) evaluator);
      }
      NetworkConfiguration partyNetConf = netConf.get(playerId);
      SecureComputationEngine<DummyArithmeticResourcePool, ProtocolBuilderNumeric> sce =
          new SecureComputationEngineImpl<>(ps, evaluator);
      TestThreadRunner.TestThreadConfiguration<
          DummyArithmeticResourcePool,
          ProtocolBuilderNumeric> ttc =
          new TestThreadRunner.TestThreadConfiguration<>(sce,
              () -> new DummyArithmeticResourcePoolImpl(playerId, noOfParties, fieldDefinition),
              () -> {
                Network asyncNetwork = new SocketNetwork(partyNetConf);
                if (logPerformance) {
                  NetworkLoggingDecorator network = new NetworkLoggingDecorator(asyncNetwork);
                  aggregate.add(network);
                  return network;
                } else {
                  return asyncNetwork;
                }
              });
      conf.put(playerId, ttc);
      performanceLoggers.putIfAbsent(playerId, aggregate);
    }

    TestThreadRunner.run(f, conf);
    PerformancePrinter printer = new DefaultPerformancePrinter();
    for (PerformanceLogger pl : performanceLoggers.values()) {
      printer.printPerformanceLog(pl);
    }
  }

  /**
   * Helper class for setting parameters in a test.
   * Any values not explicitly set will be set to the default value.
   */
  public static class TestParameters {

    private FieldDefinition modulus = DEFAULT_FIELD;
    private int maxBitLength = DEFAULT_MAX_BIT_LENGTH;
    private int fixedPointPrecesion = DEFAULT_FIXED_POINT_PRECISION;
    private int numParties = DEFAULT_PARTIES;
    private EvaluationStrategy evaluationStrategy = DEFAULT_EVALUATION_STRATEGY;
    private boolean performanceLogging = DEFAULT_PERFORMANCE_LOGGING;

    public TestParameters field(FieldDefinition field) {
      this.modulus = field;
      return this;
    }

    public TestParameters maxBitLength(int maxBitLength) {
      this.maxBitLength = maxBitLength;
      return this;
    }

    public TestParameters fixedPointPrecesion(int fixedPointPrecesion) {
      this.fixedPointPrecesion = fixedPointPrecesion;
      return this;
    }

    public TestParameters numParties(int numParties) {
      this.numParties = numParties;
      return this;
    }

    public TestParameters evaluationStrategy(EvaluationStrategy evaluationStrategy) {
      this.evaluationStrategy = evaluationStrategy;
      return this;
    }

    public TestParameters performanceLogging(boolean performanceLogging) {
      this.performanceLogging = performanceLogging;
      return this;
    }
  }
}
