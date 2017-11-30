package dk.alexandra.fresco.tools.mascot.triple;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import dk.alexandra.fresco.tools.mascot.BaseProtocol;
import dk.alexandra.fresco.tools.mascot.MascotContext;
import dk.alexandra.fresco.tools.mascot.elgen.ElGen;
import dk.alexandra.fresco.tools.mascot.field.FieldElement;
import dk.alexandra.fresco.tools.mascot.mult.MultiplyLeft;
import dk.alexandra.fresco.tools.mascot.mult.MultiplyRight;
import dk.alexandra.fresco.tools.mascot.utils.sample.DummySampler;
import dk.alexandra.fresco.tools.mascot.utils.sample.Sampler;

public class TripleGen extends BaseProtocol {

  private ElGen elGen;
  private List<MultiplyRight> rightMultipliers;
  private List<MultiplyLeft> leftMultipliers;
  // TODO re-use same sampler for triple gen and el gen?
  private Sampler sampler;
  private int numLeftFactors;
  // TODO move this into parent class/ interface?
  private boolean initialized;

  public TripleGen(MascotContext ctx, FieldElement macKeyShare, int numLeftFactors) {
    super(ctx);
    this.elGen = new ElGen(ctx, macKeyShare);
    this.leftMultipliers = new LinkedList<>();
    this.rightMultipliers = new LinkedList<>();
    Integer myId = ctx.getMyId();
    List<Integer> partyIds = ctx.getPartyIds();
    for (Integer partyId : partyIds) {
      if (!myId.equals(partyId)) {
        rightMultipliers.add(new MultiplyRight(ctx, partyId, numLeftFactors));
        leftMultipliers.add(new MultiplyLeft(ctx, partyId, numLeftFactors));
      }
    }
    this.sampler = new DummySampler(ctx.getRand());
    this.numLeftFactors = numLeftFactors;
    this.initialized = false;
  }

  public void initialize() {
    // shouldn't initialize again
    if (initialized) {
      throw new IllegalStateException("Already initialized");
    }
    // initialize el gen
    elGen.initialize();
    this.initialized = true;
  }

  // probably overdid it with streams here...
  static List<FieldElement> pairWiseAdd(List<FieldElement> group, List<FieldElement> otherGroup) {
    if (group.size() != otherGroup.size()) {
      throw new IllegalArgumentException("Groups must be same size");
    }
    Stream<FieldElement> feStream = IntStream.range(0, group.size())
        .mapToObj(idx -> {
          FieldElement el = group.get(idx);
          FieldElement otherEl = otherGroup.get(idx);
          return el.add(otherEl);
        });
    return feStream.collect(Collectors.toList());
  }

  static List<List<FieldElement>> pairWiseAddRows(List<List<FieldElement>> row,
      List<List<FieldElement>> otherRow) {
    if (row.size() != otherRow.size()) {
      throw new IllegalArgumentException("Rows must be same size");
    }
    List<List<FieldElement>> rowStreams = IntStream.range(0, row.size())
        .mapToObj(idx -> {
          List<FieldElement> group = row.get(idx);
          List<FieldElement> otherGroup = otherRow.get(idx);
          return pairWiseAdd(group, otherGroup);
        })
        .collect(Collectors.toList());
    return rowStreams;
  }

  static List<List<FieldElement>> pairWiseAdd(List<List<List<FieldElement>>> rows) {
    return rows.stream()
        .reduce((top, bottom) -> {
          return pairWiseAddRows(top, bottom);
        })
        .get();
  }

  static List<FieldElement> scalarMultiply(List<FieldElement> leftFactors,
      FieldElement rightFactor) {
    return leftFactors.stream()
        .map(lf -> lf.multiply(rightFactor))
        .collect(Collectors.toList());
  }

  static List<List<FieldElement>> pairWiseMultiply(List<List<FieldElement>> leftFactorGroups,
      List<FieldElement> rightFactors) {
    if (leftFactorGroups.size() != rightFactors.size()) {
      throw new IllegalArgumentException("Rows must be same size");
    }
    return IntStream.range(0, leftFactorGroups.size())
        .mapToObj(idx -> {
          List<FieldElement> lfg = leftFactorGroups.get(idx);
          FieldElement rf = rightFactors.get(idx);
          return scalarMultiply(lfg, rf);
        })
        .collect(Collectors.toList());
  }

  public List<List<FieldElement>> multiply(List<List<FieldElement>> leftFactorGroups,
      List<FieldElement> rightFactors) {
    // TODO should parallelize
    // TODO make factor group a class
    List<List<List<FieldElement>>> subFactors = new ArrayList<>();
    // left-mult blocks on receive, so run right mults first
    for (MultiplyRight rightMultiplier : rightMultipliers) {
      subFactors.add(rightMultiplier.multiply(rightFactors));
    }
    for (MultiplyLeft leftMultiplier : leftMultipliers) {
      subFactors.add(leftMultiplier.multiply(leftFactorGroups));
    }
    List<List<FieldElement>> localSubFactors = pairWiseMultiply(leftFactorGroups, rightFactors);
    subFactors.add(localSubFactors);
    List<List<FieldElement>> productShares = pairWiseAdd(subFactors);
    return productShares;
  }

  public void triple(int numTriples) {
    // can't generate triples before initializing
    if (!initialized) {
      throw new IllegalStateException("Need to initialize first");
    }
  }

}
