package dk.alexandra.fresco.framework.builder;

import dk.alexandra.fresco.framework.Computation;

public interface ComputationBuilderParallel<OutputT, BuilderT extends ProtocolBuilder> {

  /**
   * Applies this function to the given argument.
   *
   * @param builder the function argument
   * @return the function result
   */
  Computation<OutputT> build(BuilderT builder);

}
