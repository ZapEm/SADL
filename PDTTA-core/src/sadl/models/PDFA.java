/**
 * This file is part of SADL, a library for learning Probabilistic deterministic timed-transition Automata.
 * Copyright (C) 2013-2015  the original author or authors.
 *
 * SADL is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * SADL is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with SADL.  If not, see <http://www.gnu.org/licenses/>.
 */

package sadl.models;

import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntDoubleMap;
import gnu.trove.map.hash.TIntDoubleHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.commons.math3.exception.MathArithmeticException;
import org.apache.commons.math3.fraction.BigFraction;
import org.apache.commons.math3.util.Precision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sadl.constants.AnomalyInsertionType;
import sadl.constants.ClassLabel;
import sadl.input.TimedIntWord;
import sadl.input.TimedWord;
import sadl.interfaces.AutomatonModel;
import sadl.structure.AbnormalTransition;
import sadl.structure.Transition;
import sadl.utils.MasterSeed;

/**
 * A Probabilistic Deterministic Finite Automaton (PDFA).
 * 
 * @author Timo Klerx
 *
 */
public class PDFA implements AutomatonModel, Serializable {

	private static final long serialVersionUID = -3584763240370878883L;

	protected static final int START_STATE = 0;


	transient private static Logger logger = LoggerFactory.getLogger(PDFA.class);
	// TODO maybe change Set<Transition> transitions to Map<State,Set<Transition>>
	protected Random r = MasterSeed.nextRandom();

	protected static final double NO_TRANSITION_PROBABILITY = 0;

	TIntHashSet alphabet = new TIntHashSet();
	Set<Transition> transitions = new HashSet<>();
	TIntDoubleMap finalStateProbabilities = new TIntDoubleHashMap();
	TIntSet abnormalFinalStates = new TIntHashSet();

	/**
	 * 
	 * @return true if was not consistent and consistency was restored.
	 */
	protected boolean checkAndRestoreConsistency() {
		if (!isConsistent()) {
			return restoreConsistency();
		}
		return false;
	}

	protected boolean restoreConsistency() {
		return fixProbabilities();
	}

	protected boolean isConsistent() {
		final boolean probabilities = finalStateProbabilities.keySet().forEach(state -> checkProbability(state));
		if (!probabilities) {
			logger.info("Probabilities do not match, but will be corrected");
		}
		return probabilities;
	}

	protected boolean checkProbability(int state) {
		final List<Transition> outgoingTransitions = getTransitions(state, true);
		final double sum = outgoingTransitions.stream().mapToDouble(t -> t.getProbability()).sum();
		final boolean compareResult = Precision.equals(sum, 1);
		logger.trace("Probability sum for state {}: {} (== 1? {})", state, sum, compareResult);
		return compareResult;
	}

	protected boolean fixProbabilities() {
		final boolean fixedProbs = finalStateProbabilities.keySet().forEach(state -> fixProbability(state));
		if (fixedProbs) {
			logger.info("Probabilities were corrected.");
		}
		return fixedProbs;
	}

	protected boolean fixProbability(int state) {
		List<Transition> outgoingTransitions = getTransitions(state, true);
		final double sum = outgoingTransitions.stream().mapToDouble(t -> t.getProbability()).sum();
		// divide every probability by the sum of probabilities s.t. they sum up to 1
		if (!Precision.equals(sum, 1)) {
			logger.debug("Sum of transition probabilities for state {} is {}", state, sum);
			outgoingTransitions = getTransitions(state, true);
			outgoingTransitions.forEach(t -> changeTransitionProbability(t, t.getProbability() / sum));
			outgoingTransitions = getTransitions(state, true);
			final double newSum = outgoingTransitions.stream().mapToDouble(t -> t.getProbability()).sum();
			logger.debug("Corrected sum of transition probabilities is {}", newSum);
			if (!Precision.equals(newSum, 1.0)) {
				logger.debug("Probabilities do not sum up to one, so doing it again with the Fraction class");
				final List<BigFraction> probabilities = new ArrayList<>(outgoingTransitions.size());
				for (int i = 0; i < outgoingTransitions.size(); i++) {
					probabilities.add(i, new BigFraction(outgoingTransitions.get(i).getProbability()));
				}
				BigFraction fracSum = BigFraction.ZERO;
				for (final BigFraction f : probabilities) {
					try {
						fracSum = fracSum.add(f);
					} catch (final MathArithmeticException e) {
						logger.error("Arithmetic Exception for fracSum={}, FractionToAdd={}", fracSum, f, e);
						throw e;
					}
				}
				for (int i = 0; i < outgoingTransitions.size(); i++) {
					changeTransitionProbability(outgoingTransitions.get(i), probabilities.get(i).divide(fracSum).doubleValue());
					// outgoingTransitions.get(i).setProbability(probabilities.get(i).divide(fracSum).doubleValue());
				}
				final double tempSum = getTransitions(state, true).stream().mapToDouble(t -> t.getProbability()).sum();
				if (!Precision.equals(tempSum, 1.0)) {
					throw new IllegalStateException("Probabilities do not sum up to one, but instead to " + tempSum);
				}
			}
		}
		return true;
	}

	protected void changeTransitionProbability(Transition transition, double newProbability) {
		if (!transition.isStopTraversingTransition()) {
			final Transition t = new Transition(transition.getFromState(), transition.getToState(), transition.getSymbol(), newProbability);
			transitions.add(t);
		} else {
			final double adjusted = finalStateProbabilities.put(transition.getFromState(), newProbability);
			if (Double.doubleToLongBits(adjusted) == Double.doubleToLongBits(finalStateProbabilities.getNoEntryValue())) {
				logger.warn("Was not possible to adjust final state prob for transition {}", transition);
			}
		}
	}

	protected PDFA() {
	}

	public PDFA(Path trebaPath) throws IOException {
		final BufferedReader inputReader = Files.newBufferedReader(trebaPath, StandardCharsets.UTF_8);
		String line = "";
		// 172 172 3 0,013888888888888892
		// from state ; to state ; symbol ; probability
		while ((line = inputReader.readLine()) != null) {
			final String[] lineSplit = line.split(" ");
			if (lineSplit.length == 4) {
				final int fromState = Integer.parseInt(lineSplit[0]);
				final int toState = Integer.parseInt(lineSplit[1]);
				final int symbol = Integer.parseInt(lineSplit[2]);
				final double probability = Double.parseDouble(lineSplit[3]);
				addTransition(fromState, toState, symbol, probability);
			} else if (lineSplit.length == 2) {
				final int state = Integer.parseInt(lineSplit[0]);
				final double finalProb = Double.parseDouble(lineSplit[1]);
				addFinalState(state, finalProb);
			}
		}
	}

	public int getTransitionCount() {
		return transitions.size();
	}

	public Transition addTransition(int fromState, int toState, int symbol, double probability) {
		addState(fromState);
		addState(toState);
		alphabet.add(symbol);
		final Transition t = new Transition(fromState, toState, symbol, probability);
		transitions.add(t);
		return t;
	}

	public Transition addAbnormalTransition(int fromState, int toState, int symbol, double probability, AnomalyInsertionType anomalyType) {
		addState(fromState);
		addState(toState);
		alphabet.add(symbol);
		final Transition t = new AbnormalTransition(fromState, toState, symbol, probability, anomalyType);
		transitions.add(t);
		return t;
	}

	public Transition addAbnormalTransition(Transition t, AnomalyInsertionType anomalyType) {
		return addAbnormalTransition(t.getFromState(), t.getToState(), t.getSymbol(), t.getProbability(), anomalyType);
	}

	protected void addState(int state) {
		if (!finalStateProbabilities.containsKey(state)) {
			// finalStateProbabilities is also the set of states. so add the state to this set with a probability of zero
			addFinalState(state, NO_TRANSITION_PROBABILITY);
		}
	}

	protected Transition getFinalTransition(int state) {
		if (abnormalFinalStates.contains(state)) {
			return new AbnormalTransition(state, state, Transition.STOP_TRAVERSING_SYMBOL, finalStateProbabilities.get(state), AnomalyInsertionType.TYPE_FIVE);
		} else {
			return new Transition(state, state, Transition.STOP_TRAVERSING_SYMBOL, finalStateProbabilities.get(state));
		}
	}

	public void toGraphvizFile(Path graphvizResult, boolean compressed) throws IOException {
		final BufferedWriter writer = Files.newBufferedWriter(graphvizResult, StandardCharsets.UTF_8);
		writer.write("digraph G {\n");
		// start states
		writer.write("qi [shape = point ];");
		// write states
		for (final int state : finalStateProbabilities.keys()) {
			writer.write(Integer.toString(state));
			writer.write(" [shape=");
			final boolean abnormal = getFinalTransition(state).isAbnormal();
			final double finalProb = getFinalStateProbability(state);
			if (finalProb > 0 || (compressed && finalProb > 0.01)) {
				writer.write("double");
			}
			writer.write("circle");
			if (abnormal) {
				writer.write(", color=red");
			}
			if (finalProb > 0 || (compressed && finalProb > 0.01)) {
				writer.write(", label=\"");
				writer.write(Integer.toString(state));
				writer.write("&#92;np= ");
				writer.write(Double.toString(Precision.round(finalProb, 2)));
				writer.write("\"");
			}
			writer.write("];\n");
		}
		writer.write("qi -> 0;");
		// write transitions
		for (final Transition t : transitions) {
			if (compressed && t.getProbability() <= 0.01) {
				continue;
			}
			// 0 -> 0 [label=0.06];
			writer.write(Integer.toString(t.getFromState()));
			writer.write(" -> ");
			writer.write(Integer.toString(t.getToState()));
			writer.write(" [label=<");
			writer.write(Integer.toString(t.getSymbol()));
			if (t.getProbability() > 0) {
				writer.write(" p=");
				writer.write(Double.toString(Precision.round(t.getProbability(), 2)));
			}
			if (t.isAbnormal()) {
				writer.write("<BR/>");
				writer.write("<FONT COLOR=\"red\">");
				writer.write(Integer.toString(t.getAnomalyInsertionType().getTypeIndex()));
				writer.write("</FONT>");
			}
			writer.write(">");
			if (t.isAbnormal()) {
				writer.write(" color=\"red\"");
			}
			writer.write(";];\n");

		}
		writer.write("}");
		writer.close();

	}

	public void addFinalState(int state, double probability) {
		finalStateProbabilities.put(state, probability);
	}

	protected void addAbnormalFinalState(int state, double probability) {
		addFinalState(state, probability);
		abnormalFinalStates.add(state);
	}

	public double getFinalStateProbability(int state) {
		return finalStateProbabilities.get(state);
	}

	public double getTransitionProbability(int fromState, int toState, int symbol) {
		for (final Transition t : transitions) {
			if (t.getFromState() == fromState && t.getToState() == toState && t.getSymbol() == symbol) {
				return t.getProbability();
			}
		}
		return NO_TRANSITION_PROBABILITY;
	}

	public Transition getTransition(int currentState, int event) {
		Transition result = null;
		if (event == Transition.STOP_TRAVERSING_SYMBOL) {
			result = getFinalTransition(currentState);
		} else {
			for (final Transition t : transitions) {
				if (t.getFromState() == currentState && t.getSymbol() == event) {
					if (result != null) {
						logger.error("Found more than one transition for state " + currentState + " and event " + event);
					}
					result = t;
				}
			}
		}
		return result;
	}



	protected boolean removeTransition(Transition t) {
		final boolean wasRemoved = transitions.remove(t);
		if (!wasRemoved) {
			logger.warn("Tried to remove a non existing transition={}", t);
		}
		return wasRemoved;
	}

	protected static final int MAX_SEQUENCE_LENGTH = 1000;

	public TimedWord sampleSequence() {
		int currentState = START_STATE;

		final TIntList eventList = new TIntArrayList();
		final TIntList timeList = new TIntArrayList();
		boolean choseFinalState = false;
		while (!choseFinalState) {
			final List<Transition> possibleTransitions = getTransitions(currentState, true);
			Collections.sort(possibleTransitions, (t1, t2) -> -Double.compare(t2.getProbability(), t1.getProbability()));
			final double random = r.nextDouble();
			double summedProbs = 0;
			int index = -1;
			for (int i = 0; i < possibleTransitions.size(); i++) {
				summedProbs += possibleTransitions.get(i).getProbability();
				if (random < summedProbs) {
					index = i;
					break;
				}
			}

			final Transition chosenTransition = possibleTransitions.get(index);
			if (chosenTransition.isStopTraversingTransition()) {
				choseFinalState = true;
			} else if (eventList.size() > MAX_SEQUENCE_LENGTH) {
				throw new IllegalStateException("A sequence longer than " + MAX_SEQUENCE_LENGTH + " events should have been generated");
			} else {
				currentState = chosenTransition.getToState();
				eventList.add(chosenTransition.getSymbol());
			}
		}
		return new TimedIntWord(eventList, timeList, ClassLabel.NORMAL);
	}

	/**
	 * Returns all outgoing probabilities from the given state
	 * 
	 * @param currentState
	 *            the given state
	 * @param includeStoppingTransition
	 *            whether to include final transition probabilities
	 * @return
	 */
	protected List<Transition> getTransitions(int currentState, boolean includeStoppingTransition) {
		final List<Transition> result = new ArrayList<>();
		for (final Transition t : transitions) {
			if (t.getFromState() == currentState) {
				result.add(t);
			}
		}
		if (includeStoppingTransition) {
			for (final int state : finalStateProbabilities.keys()) {
				if (state == currentState) {
					result.add(getFinalTransition(state));
				}
			}
		}
		return result;
	}

	public Random getRandom() {
		return r;
	}

	public void setRandom(Random r) {
		this.r = r;
	}

	protected boolean isInAutomaton(TimedWord s) {
		int currentState = START_STATE;
		for (int i = 0; i < s.length(); i++) {
			final int nextEvent = s.getIntSymbol(i);
			final Transition t = getTransition(currentState, nextEvent);
			if (t == null) {
				return false;
			}
			currentState = t.getToState();
		}
		if (getFinalStateProbability(currentState) > NO_TRANSITION_PROBABILITY) {
			return true;
		} else {
			return false;
		}
	}

	public int getStartState() {
		return START_STATE;
	}

	public int getStateCount() {
		return finalStateProbabilities.size();
	}

	protected void removeState(int i) {
		finalStateProbabilities.remove(i);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((abnormalFinalStates == null) ? 0 : abnormalFinalStates.hashCode());
		result = prime * result + ((alphabet == null) ? 0 : alphabet.hashCode());
		result = prime * result + ((finalStateProbabilities == null) ? 0 : finalStateProbabilities.hashCode());
		result = prime * result + ((transitions == null) ? 0 : transitions.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (!(obj instanceof PDTTA)) {
			return false;
		}
		final PDTTA other = (PDTTA) obj;
		if (abnormalFinalStates == null) {
			if (other.abnormalFinalStates != null) {
				return false;
			}
		} else if (!abnormalFinalStates.equals(other.abnormalFinalStates)) {
			return false;
		}
		if (alphabet == null) {
			if (other.alphabet != null) {
				return false;
			}
		} else if (!alphabet.equals(other.alphabet)) {
			return false;
		}
		if (finalStateProbabilities == null) {
			if (other.finalStateProbabilities != null) {
				return false;
			}
		} else if (!finalStateProbabilities.equals(other.finalStateProbabilities)) {
			return false;
		}
		if (transitions == null) {
			if (other.transitions != null) {
				return false;
			}
		} else if (!transitions.equals(other.transitions)) {
			int count = 0;
			for (final Transition t : transitions) {
				if (!other.transitions.contains(t)) {
					logger.error("Transition {} not contained in other.transitions", t);
					count++;
				}
			}
			for (final Transition t : other.transitions) {
				if (!transitions.contains(t)) {
					logger.error("Transition {} not contained in transitions", t);
					count++;
				}
			}
			if (count > 0) {
				logger.error("{} out of {} transitions did not match", count, transitions.size());
			}
			return false;
		}
		return true;
	}


}
