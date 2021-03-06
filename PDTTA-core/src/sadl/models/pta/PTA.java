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

package sadl.models.pta;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.ListIterator;

import sadl.constants.PTAOrdering;
import sadl.input.TimedInput;
import sadl.input.TimedWord;
import sadl.models.pdta.PDTA;
import sadl.models.pdta.PDTAState;

public class PTA {

	protected PTAState root;
	protected LinkedHashMap<Integer, PTAState> tails = new LinkedHashMap<>();
	protected HashMap<String, Event> events;
	protected int depth = 0;

	protected LinkedList<PTAState> states = new LinkedList<>();
	protected LinkedList<PTATransition> transitions = new LinkedList<>();

	protected boolean statesMerged = false;

	public PTA(HashMap<String, Event> events) {
		this.events = events;
		this.root = new PTAState("", null, this);
		states.add(root);
		tails.put(root.getId(), root);
	}

	public PTA(HashMap<String, Event> events, TimedInput timedSequences) {
		this(events);
		this.addSequences(timedSequences);
	}

	public HashMap<String, Event> getEvents() {

		return events;
	}

	// TODO getTransitions

	public LinkedHashMap<Integer, PTAState> getTails() {

		return tails;
	}

	public LinkedList<PTAState> getStates() {

		return states;
	}

	public void setStates(LinkedList<PTAState> states) {

		this.states = states;
	}

	public PTAState getRoot() {

		return root;
	}

	public int getDepth() {

		return depth;
	}

	public void addSequences(TimedInput timedSequences) {

		if (timedSequences == null) {
			throw new IllegalArgumentException();
		}

		for (final TimedWord sequence : timedSequences) {
			this.addSequence(sequence);
		}

	}

	public void addSequence(TimedWord sequence) {

		PTAState currentState = root;
		boolean addTail = false;

		int i;
		for (i = 0; i < sequence.length(); i++) {
			final String eventSymbol = sequence.getSymbol(i);
			final double time = sequence.getTimeValue(i);
			final Event event = events.get(eventSymbol);

			if (event == null){
				throw new IllegalArgumentException("Event " + eventSymbol + " not exists: " + sequence.toString());
			}

			final SubEvent subEvent = event.getSubEventByTime(time);

			final PTATransition transition = currentState.getTransition(subEvent.getSymbol());

			if (transition == null){
				tails.remove(currentState.id);
				addTail = true;

				final PTAState nextState = new PTAState(currentState.getWord() + eventSymbol, currentState, this);

				final PTATransition newTransition = new PTATransition(currentState, nextState, subEvent, 1);
				newTransition.add();

				currentState = nextState;
				states.add(currentState);

				break;
			}

			transition.incrementCount(1);
			currentState = transition.getTarget();
		}

		for (i = i + 1; i < sequence.length(); i++) {
			final String eventSymbol = sequence.getSymbol(i);
			final double time = sequence.getTimeValue(i);
			final Event event = events.get(eventSymbol);

			if (event == null){
				throw new IllegalArgumentException("Event " + eventSymbol + " not exists: " + sequence.toString());
			}

			final SubEvent subEvent = event.getSubEventByTime(time);

			final PTAState nextState = new PTAState(currentState.getWord() + eventSymbol, currentState, this);

			final PTATransition newTransition = new PTATransition(currentState, nextState, subEvent, 1);
			// newTransition.addTimeValue(time);
			newTransition.add();

			currentState = nextState;
			states.add(currentState);
		}

		if (addTail) {
			tails.put(currentState.getId(), currentState);
		}

		if (sequence.length() > depth) {
			depth = sequence.length();
		}

	}

	public LinkedList<PTAState> getStatesOrdered(PTAOrdering order) {

		final LinkedList<PTAState> orderedStates = new LinkedList<>();

		if (order == PTAOrdering.TopDown) {
			LinkedList<PTAState> heads = new LinkedList<>();

			for (final PTATransition transition : root.getOutTransitions()) {
				final PTAState state = transition.getTarget();
				heads.add(state);
			}

			while (!heads.isEmpty()) {
				orderedStates.addAll(heads);

				final LinkedList<PTAState> nextHeads = new LinkedList<>();

				for (final PTAState headState : heads) {
					for (final PTATransition transition : headState.getOutTransitions()) {
						final PTAState nextState = transition.getTarget();
						nextHeads.add(nextState);
					}
				}

				heads = nextHeads;
			}
		} else if (order == PTAOrdering.BottomUp) {
			LinkedHashMap<Integer, PTAState> currentTails = tails;

			for (final PTAState tailState : currentTails.values()) {
				tailState.mark();
			}

			while (!currentTails.isEmpty()) {
				orderedStates.addAll(currentTails.values());

				final LinkedHashMap<Integer, PTAState> nextTails = new LinkedHashMap<>(currentTails.size());

				for (final PTAState tailState : currentTails.values()) {
					final PTAState fatherState = tailState.getFatherState();
					if (fatherState != root && !fatherState.isMarked()) {
						nextTails.putIfAbsent(fatherState.getId(), fatherState);
						fatherState.mark();
					}
				}

				currentTails = nextTails;
			}
		} else {
			throw new IllegalArgumentException();
		}

		return orderedStates;
	}

	public void mergeTransitionsInCriticalAreas() {

		for (final ListIterator<PTAState> statesIterator = states.listIterator(); statesIterator.hasNext();) {
			final PTAState state = statesIterator.next();

			if (!state.exists()) {
				statesIterator.remove();
			} else {
				state.removeCriticalTransitions();
			}
		}
	}

	public PDTA toPDTA() {

		final HashMap<Integer, PDTAState> pdtaStates = new HashMap<>();

		for (final ListIterator<PTAState> iterator = states.listIterator(); iterator.hasNext();) {
			final PTAState ptaState = iterator.next();

			if (ptaState.exists()) {
				final int stateId = ptaState.getId();
				pdtaStates.put(stateId, new PDTAState(stateId, ptaState.getEndProbability()));
			}
			else {
				iterator.remove();
			}
		}

		for (final PTAState ptaState : states) {
			final PDTAState pdrtaStateSource = pdtaStates.get(ptaState.getId());
			final int outTransitionsCount = ptaState.getOutTransitionsCount();
			final int endCount = ptaState.getEndCount();

			for (final PTATransition transition : ptaState.outTransitions.values()) {
				final PDTAState pdrtaStateTarget = pdtaStates.get(transition.getTarget().getId());
				final SubEvent event = transition.getEvent();

				pdrtaStateSource.addTransition(event, pdrtaStateTarget, event.getIntervalInState(ptaState), (double) transition.getCount()
						/ (outTransitionsCount + endCount));
			}
		}

		return new PDTA(pdtaStates.get(root.getId()), pdtaStates, events);
	}

	public void toGraphvizFile(Path resultPath) throws IOException {

		final BufferedWriter writer = Files.newBufferedWriter(resultPath, StandardCharsets.UTF_8);
		writer.write("digraph G {\n");

		// write states
		for (final PTAState state : states) {
			if (state.exists()) {
				writer.write(Integer.toString(state.getId()));
				writer.write(" [shape=circle, label=\"" + Integer.toString(state.getId()) + "\"");

				if (tails.containsKey(state.getId())) {
					writer.write(", color=red");
				}

				writer.write("]\n");
			}
		}

		for (final PTATransition transition : transitions) {
			if (transition.exists()) {
				writer.write(Integer.toString(transition.getSource().getId()) + "->" + Integer.toString(transition.getTarget().getId()) + " [label=<"
						+ transition.getEvent().getSymbol() + "(" + transition.getCount() + ")>;];\n");
			}
		}
		writer.write("}");
		writer.close();
	}

	private void cleanUp(){

		for (final Iterator<PTAState> statesIterator = states.iterator(); statesIterator.hasNext();) {
			final PTAState state = statesIterator.next();

			if (!state.exists()) {
				statesIterator.remove();
			}
		}

		for (final Iterator<PTAState> statesIterator = tails.values().iterator(); statesIterator.hasNext();) {
			final PTAState state = statesIterator.next();

			if (!state.exists()) {
				statesIterator.remove();
			}
		}

		for (final Iterator<PTATransition> transitionsIterator = transitions.iterator(); transitionsIterator.hasNext();) {
			final PTATransition transition = transitionsIterator.next();

			if (!transition.exists()) {
				transitionsIterator.remove();
			}
		}

	}

}
