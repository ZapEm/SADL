/**
 * This file is part of SADL, a library for learning all sorts of (timed) automata and performing sequence-based anomaly detection.
 * Copyright (C) 2013-2015  the original author or authors.
 *
 * SADL is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * SADL is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with SADL.  If not, see <http://www.gnu.org/licenses/>.
 */

package sadl.modellearner;

import gnu.trove.list.TDoubleList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jsat.distributions.Distribution;
import jsat.distributions.empirical.kernelfunc.KernelFunction;

import org.apache.commons.lang3.SerializationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sadl.input.TimedInput;
import sadl.input.TimedWord;
import sadl.models.TauPTA;
import sadl.structure.Transition;
import sadl.structure.ZeroProbTransition;

public class TauPtaLearner extends PdttaLearner {
	private static Logger logger = LoggerFactory.getLogger(TauPtaLearner.class);

	TObjectIntMap<Transition> transitionCount = new TObjectIntHashMap<>();
	TIntIntMap finalStateCount = new TIntIntHashMap();

	public TauPtaLearner() {
		super(null, null, -1);
	}

	public TauPtaLearner(KernelFunction kdeKernelFunction, double kdeBandwidth) {
		super(null, kdeKernelFunction, kdeBandwidth);
	}

	public TauPtaLearner(KernelFunction kdeKernelFunction) {
		super(null, kdeKernelFunction, -1);
	}

	public TauPtaLearner(double kdeBandwidth) {
		super(null, null, kdeBandwidth);
	}

	int ommitedSequenceCount = 0;

	@Override
	public TauPTA train(TimedInput trainingSequences) {
		trainingSequences = SerializationUtils.clone(trainingSequences);
		trainingSequences.toTimedIntWords();
		final TauPTA initialPta = new TauPTA();
		initialPta.addState(TauPTA.START_STATE);

		for (final TimedWord s : trainingSequences) {
			addEventSequence(initialPta, s);
		}

		// remove transitions and ending states with less than X occurences
		final double threshold = TauPTA.SEQUENCE_OMMIT_THRESHOLD * trainingSequences.size();
		for (final int state : initialPta.getStates()) {
			final List<Transition> stateTransitions = initialPta.getTransitions(state, false);
			for (final Transition t : stateTransitions) {
				if (transitionCount.get(t) < threshold) {
					initialPta.removeTimedTransition(t, false);
				}
			}
			if (finalStateCount.get(state) < threshold) {
				finalStateCount.put(state, 0);
			}
		}

		// compute event probabilities from counts
		for (final int state : initialPta.getStates()) {
			final List<Transition> stateTransitions = initialPta.getTransitions(state, false);
			int occurenceCount = 0;
			for (final Transition t : stateTransitions) {
				occurenceCount += transitionCount.get(t);
			}
			occurenceCount += finalStateCount.get(state);
			for (final Transition t : stateTransitions) {
				initialPta.changeTransitionProbability(t, transitionCount.get(t) / (double) occurenceCount, false);
			}
			initialPta.addFinalState(state, finalStateCount.get(state) / (double) occurenceCount);
		}
		finalStateCount.clear();
		transitionCount.clear();
		// now the whole stuff again but only with those sequences that are in the initialPta
		// do not remove any sequences because they should occur more often than the specified threshold
		final TauPTA newPta = new TauPTA();
		newPta.addState(TauPTA.START_STATE);

		for (final TimedWord s : trainingSequences) {
			if (initialPta.isInAutomaton(s)) {
				addEventSequence(newPta, s);
			}
		}

		// compute event probabilities from counts
		for (final int state : newPta.getStates()) {
			final List<Transition> stateTransitions = newPta.getTransitions(state, false);
			int occurenceCount = 0;
			for (final Transition t : stateTransitions) {
				occurenceCount += transitionCount.get(t);
			}
			occurenceCount += finalStateCount.get(state);
			for (final Transition t : stateTransitions) {
				newPta.changeTransitionProbability(t, transitionCount.get(t) / (double) occurenceCount, false);
			}
			newPta.addFinalState(state, finalStateCount.get(state) / (double) occurenceCount);
		}

		// compute time probabilities
		final Map<ZeroProbTransition, TDoubleList> timeValueBuckets = new HashMap<>();
		for (final TimedWord s : trainingSequences) {
			if (newPta.isInAutomaton(s)) {
				int currentState = TauPTA.START_STATE;
				for (int i = 0; i < s.length(); i++) {
					final int nextEvent = s.getIntSymbol(i);
					final Transition t = newPta.getTransition(currentState, nextEvent);
					if (t == null) {
						// this should never happen!
						throw new IllegalStateException("Did not get a transition, but checked before that there must be transitions for this sequence " + s);
					}
					addTimeValue(timeValueBuckets, t.getFromState(), t.getToState(), t.getSymbol(), s.getTimeValue(i));
					currentState = t.getToState();
				}
			} else {
				ommitedSequenceCount++;
			}
		}
		logger.info("OmmitedSequenceCount={} out of {} sequences at a threshold of less than {} absolute occurences.", ommitedSequenceCount,
				trainingSequences.size(), TauPTA.SEQUENCE_OMMIT_THRESHOLD * trainingSequences.size());
		final Map<ZeroProbTransition, Distribution> distributions = fit(timeValueBuckets);
		newPta.setTransitionDistributions(distributions);
		if (distributions.size() != newPta.getTransitionCount()) {
			final List<Transition> missingDistributions = new ArrayList<>();
			for (final Transition t : newPta.getAllTransitions()) {
				if (distributions.get(t.toZeroProbTransition()) == null) {
					missingDistributions.add(t);
				}
			}
			System.out.println(missingDistributions);
			throw new IllegalStateException("It is not possible to more/less distributions than transitions (" + distributions.size() + "/"
					+ newPta.getTransitionCount() + ").");
			// compute what is missing in the distribution set
		}
		newPta.makeImmutable();
		return newPta;
	}

	private void addEventSequence(TauPTA pta, TimedWord s) {
		int currentState = pta.START_STATE;

		for (int i = 0; i < s.length(); i++) {
			final int nextEvent = s.getIntSymbol(i);
			Transition t = pta.getTransition(currentState, nextEvent);
			if (t == null) {
				t = pta.addTransition(currentState, pta.getStateCount(), nextEvent, pta.NO_TRANSITION_PROBABILITY);
				transitionCount.put(t, 0);
			}
			transitionCount.increment(t);
			currentState = t.getToState();
		}
		// add final state count
		finalStateCount.adjustOrPutValue(currentState, 1, 1);
	}

}
