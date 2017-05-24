package org.ggp.base.player.gamer.statemachine.frankie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.cache.CachedStateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.ProverStateMachine;

public class FrankieTestingGamer extends FrankieGamer {

	// Settings and helpers
	private long buffer;
	private int nThreads;

	// Agent components
	private Role agent;
	private List<Role> roles;
	private StateMachine stateMachine;
	private List<StateMachine> machines;
	private Timer timer;
	private MonteCarloTreeSearch searchFn;

	private int turn;

	// ----- Initialization and Pre-Computation ----- //
	@Override
	public void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		System.out.println("================= New Game =================");
		System.out.println(getName());	// Print the agent's name

		// Configure Settings
		buffer = 4500;
		long metagamebuffer = 6000;
		nThreads = 2;
		boolean useSpeedTestToDetermineThreads = true;
		int speedTestDuration = 5000;

		// Start Timer
		long finishBy = timeout - metagamebuffer;
		timer = new Timer();
		timer.initTimer(timeout, finishBy);

		// Additional Setup
		stateMachine = getStateMachine();
		agent = getRole();
		roles = stateMachine.getRoles();
		turn = 0;

		if(useSpeedTestToDetermineThreads){
			double chargesPerSec = performSpeedTest(speedTestDuration);
			if(chargesPerSec > 100) nThreads = 1;
			else nThreads = 2;
		}

		// Initialize statemachines for other threads
		System.out.println("nThreads: " + nThreads);
		machines = new ArrayList<StateMachine>(nThreads);
		for(int i = 0; i<nThreads; i++){
			StateMachine sm = null;

			System.out.println("Creating instance of "+ statemachinetype);
			if(statemachinetype == "PropNetStateMachine"){
				sm = new CachedStateMachine(new PropNetStateMachine());
			}
			else if (statemachinetype == "ProverStateMachine"){
				sm = new CachedStateMachine(new ProverStateMachine());
			}
			else assert(false);

			sm.initialize(getMatch().getGame().getRules());
			machines.add(sm);
		}

		// Determine if game is single-player or multi-player and init MCTS
		if(roles.size() > 1){
			System.out.println("Multi-Player Game");
			searchFn = new MultiThreadedMultiPlayerMonteCarloTreeSearch(stateMachine, agent, timer, machines);
		}
		else {
			System.out.println("Single-Player Game");
			searchFn = new SinglePlayerMonteCarloTreeSearch(stateMachine, agent, timer);
		}

		// Start computing the game tree during meta game
		MachineState currentState = getCurrentState();
		assert(currentState != null);

		Node root = searchFn.getRoot(currentState);
		searchFn.MCTS(root);
		searchFn.printTree(root, 1, 0);
		System.out.println("Number of Initial Depth Charges: " + root.visits);

		if(timer.isExpired()){
			System.out.println("METAGAMING TIMER IS EXPIRED");
		}
	}

	// ----- Select Move ----- //
	@Override
	public Move stateMachineSelectMove(long timeout)
			throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException {

		StateMachine stateMachine = getStateMachine();
		long start = System.currentTimeMillis();	// Start timer
		long finishBy = timeout - buffer;

		turn += 1;
		System.out.println(" ------------- Turn " + turn + " ------------- ");

		timer.initTimer(timeout, finishBy);

		// Get randomized list of moves
		List<Move> moves = stateMachine.getLegalMoves(getCurrentState(), agent);
		moves = new ArrayList<Move>(moves);
		Collections.shuffle(moves);	// Randomize move order

		// Select an Action
		System.out.println("Number of Moves: " + moves.size());
		Move action = moves.get(0);

		action = searchFn.getAction(moves, getCurrentState());

		long stop = System.currentTimeMillis();		// Stop timer

		// Don't touch
		notifyObservers(new GamerSelectedMoveEvent(moves, action, stop - start));

		if(timer.isExpired()){
			buffer += 500;	// Increase buffer if we timeout
			System.out.println("TIMER IS EXPIRED");
		}
		return action;
	}


	@Override
	public void stateMachineStop() {
		// Cleanup when the match ends normally
		try {
			int reward = stateMachine.getGoal(getCurrentState(), agent);
			System.out.println("Game over. Final Reward: " + reward);

		} catch (GoalDefinitionException e) {
			System.out.println("Goal Definition Exception: Failed to retrive final reward");
		}
	}


	public double performSpeedTest(int duration) throws TransitionDefinitionException, MoveDefinitionException{
		System.out.println("Performing speed test");
		long start = System.currentTimeMillis();	// Start timer
		long testTime = start + duration;
		Timer speedTestTimer = new Timer();
		speedTestTimer.initTimer(0, testTime);
		int count = 0;
		while(!speedTestTimer.isOutOfTime()) {
			count ++;
			stateMachine.performDepthChargeLite(getCurrentState());
		}
		double chargesPerSec = 1000 * count / ((double)duration);
		System.out.println("Charges Per Second: " + chargesPerSec);

		return chargesPerSec;
	}
}