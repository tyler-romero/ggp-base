package org.ggp.base.player.gamer.statemachine.frankie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;


public abstract class AbstractMonteCarloTreeSearch extends GenericSearch{
	List<Role> roles;
	static double C;

	AbstractMonteCarloTreeSearch(StateMachine sm, Role a, Timer t){
		super(sm, a, t);
		roles = stateMachine.getRoles();
		// Settings
		C = 40.0; // optimism parameter
	}

	public void MCTS(List<Node> roots) throws MoveDefinitionException, TransitionDefinitionException{
		throw new Error();
	}

	@Override
	public abstract Move getAction(List<Move> moves, MachineState currentState) throws MoveDefinitionException, TransitionDefinitionException;

	public void MCTS(Node root) throws MoveDefinitionException, TransitionDefinitionException{
		throw new Error();
	}

	public void MCTS(MachineState root) throws MoveDefinitionException, TransitionDefinitionException{
		throw new Error();
	}
}

class MonteCarloTreeSearch extends AbstractMonteCarloTreeSearch{

	Random randomizer = new Random();
	Node root = null;
	boolean metagaming = true;

	MonteCarloTreeSearch(StateMachine sm, Role a, Timer t) {
		super(sm, a, t);
		System.out.println("MonteCarloTreeSearch");
	}

	@Override
	public void metaGame(MachineState currentState) throws MoveDefinitionException, TransitionDefinitionException{
		Node root = getRoot(currentState);
		MCTS(root);
	}

	Node getRoot(MachineState currentState) throws MoveDefinitionException {
		if(root == null){
			System.out.println("Creating new game tree root");
			root = new Node(currentState, null, null);
			return root;
		}

		if(metagaming)	return root;	// Return the root that was expanded during metagaming

		for(Node child: root.children) {
			if(child.state.equals(currentState)) {
				root = child;
				root.parent = null;
				return root;
			}
		}
		System.out.println("New root not found in children. Creating new game tree root.");
		root = new Node(currentState, null, null);
		return root;
	}

	@Override
	public Move getAction(List<Move> moves, MachineState currentState) throws MoveDefinitionException, TransitionDefinitionException {
		root = getRoot(currentState);
		if(metagaming) metagaming = false;

		System.out.println("Num recycled depth charges: " + root.visits);

		// Search the tree
		MCTS(root);	// Uses timer to terminate

		// Select the best action from the children.
		Move bestAction = moves.get(0);
		double score;
		if(root.isMin(stateMachine, agent)){	// Min case: Only one possible action. Find min action value.
			score = 100.0;
			for(Node child : root.children) {
				double result = child.get_value();
				if (result < score)
					score = result;
			}
		} else {								// Max case: Find best action. Find max action value.
			score = 0;
			for(Node child : root.children) {
				double result = child.get_value();
				if (result > score && child.visits > 1) {
					score = result;
					bestAction = child.action;
				}
			}
		}

		printTree(root, 1, 0);
		System.out.println("Num depth charges: " + root.visits);
		System.out.println("State/Action Value: " + score);
		return bestAction;
	}

	@Override
	public void MCTS(Node root) throws MoveDefinitionException, TransitionDefinitionException {
		while(!timer.isOutOfTime()) {
			Node node_to_expand = select(root);
			if(timer.isOutOfTime()) break;
			Node node_to_evaluate = expand(node_to_expand);
			if(timer.isOutOfTime()) break;
			double score = simulate(node_to_evaluate);
			if(timer.isOutOfTime()) break;
			backprop(node_to_evaluate, score);
		}
	}

	Node select(Node node) throws MoveDefinitionException {
		//System.out.println("select");
		if(node.children.size() == 0) return node;
		if(node.visits == 0) return node;

		// Evenly search each child to an arbitrary threshold
		for(Node child : node.children){
			if(child.visits==0)
				return child;
		}

		// Some epsilon greediness to help exploration
		Node result = null;
		if(randomizer.nextDouble() < 0.05){
			result = node.children.get(randomizer.nextInt(node.children.size()));
			select(result);
		}

		// If all children have been visited, select a child to recurse on
		double score = -Double.MAX_VALUE;
		for(Node child : node.children){
			double newscore = selectfn(child);
			if (newscore > score) {
				score = newscore;
				result = child;
			}
		}
		return select(result);
	}

	protected
	double selectfn(Node node) throws MoveDefinitionException{
		// A formula based on Lower Confidence Bounds (How pessimistic we are when its our opponents turn)
		if(node.parent.isMin(stateMachine, agent)){
			return -1*(node.get_value() - C*Math.sqrt(Math.log(node.parent.visits)/node.visits));
		}
		// A formula based on Upper Confidence Bounds (How optimistic we are when its our turn)
		return node.get_value() + C*Math.sqrt(Math.log(node.parent.visits)/node.visits);
	}

	Node expand(Node node) throws MoveDefinitionException, TransitionDefinitionException {
		// First check if this node is a terminal node. If it is, return it.
		if (stateMachine.isTerminal(node.state)) {
			return node;
		}

		// Create child nodes from all joint legal moves. NOTE: could be expensive
		List<Move> actions = stateMachine.findLegals(agent, node.state);
		actions = new ArrayList<Move>(actions);
		Collections.shuffle(actions);

		for(Move action: actions) {
			List<List<Move>> joint_move_list = stateMachine.getLegalJointMoves(node.state, agent, action);
			for(List<Move> joint_move : joint_move_list) {
				MachineState newstate = stateMachine.getNextState(node.state, joint_move);
				Node newnode = new Node(newstate, node, action);
				node.children.add(newnode);
			}
		}

		// Randomly return one of the child nodes
		return node.children.get(randomizer.nextInt(node.children.size()));
	}

	double simulate(Node node) throws MoveDefinitionException, TransitionDefinitionException {
		double reward = 0.0;
		MachineState mcFinalState = stateMachine.performDepthCharge(node.state, null);
		try{
			reward = stateMachine.findReward(agent, mcFinalState);
		}
		catch (GoalDefinitionException e){
			System.out.println("GoalDefinitionException in Monte Carlo");
		}
		return reward;
	}

	protected
	void backprop(Node node, double score) {
		node.visits = node.visits + 1;
		node.utility = node.utility + score;
		if(node.parent != null) {
			backprop(node.parent, score);
		}
	}

	void printTree(Node node, int max_depth, int depth) throws MoveDefinitionException {
		if(depth > max_depth) return;
		if(depth == 0 && max_depth != 0) System.out.println("-----");
		StringBuffer outputBuffer = new StringBuffer(depth);
		for (int i = 0; i < depth; i++){
		   outputBuffer.append("\t");
		}
		String tabs = outputBuffer.toString();

		String bool;
		if(node.isMin(stateMachine, agent)) bool = "min";
		else bool = "max";

		// pre-order
		System.out.println(tabs + "(" + node.get_value() + "/" + node.visits + ") " + bool);
		for(Node child : node.children){
			printTree(child, max_depth, depth+1);
		}

		if(depth == 0 && max_depth != 0) System.out.println("-----");
	}
}


// --------------- Implementations --------------------
class LeafMultiThreadedMonteCarloTreeSearch extends MonteCarloTreeSearch{
	// Depending on the game could be faster or slower than single threaded version.
	DepthChargeManager dmManager;

	LeafMultiThreadedMonteCarloTreeSearch(StateMachine sm, Role a, Timer t, List<StateMachine> machines) {
		super(sm, a, t);
		System.out.println("LeafMultiThreadedMonteCarloTreeSearch");
		dmManager = new DepthChargeManager(machines, agent);
	}

	@Override
	public void MCTS(Node root) throws MoveDefinitionException, TransitionDefinitionException {
		while(!timer.isOutOfTime()) {
			Node node_to_expand = select(root);
			Node node_to_evaluate = expand(node_to_expand);
			List<Integer> scores = simulateParallel(node_to_evaluate);

			int scoreSum = 0;
			for(int score: scores){
				scoreSum += score;
			}

			backpropParallel(node_to_evaluate, scoreSum, scores.size());
		}
	}

	List<Integer> simulateParallel(Node node) throws MoveDefinitionException, TransitionDefinitionException {
		return dmManager.performDepthCharges(node.state);
	}

	void backpropParallel(Node node, int scoreSum, int newVisits) {
		node.visits = node.visits + newVisits;
		node.utility = node.utility + scoreSum;
		if(node.parent != null)
			backpropParallel(node.parent, scoreSum, newVisits);
	}
}


class TreeMultiThreadedMonteCarloTreeSearch extends MonteCarloTreeSearch {
	// Depending on the game could be faster or slower than single threaded version.
	static int nThreads;
	List<StateMachine> machines;

	// Thread pooling
	ExecutorService executor;
	CompletionService<Integer> completionService;

	TreeMultiThreadedMonteCarloTreeSearch(StateMachine sm, Role a, Timer t, List<StateMachine> m) {
		super(sm, a, t);
		System.out.println("TreeMultiThreadedMonteCarloTreeSearch");
		machines = m;
		nThreads = machines.size();

		executor = Executors.newFixedThreadPool(nThreads);
		completionService = new ExecutorCompletionService<Integer>(executor);
	}

	@Override
	public Move getAction(List<Move> moves, MachineState currentState) throws MoveDefinitionException, TransitionDefinitionException {
		root = getRoot(currentState);
		if(metagaming) metagaming = false;

		System.out.println("Num recycled depth charges: " + root.visits);

		// Search the tree
		MCTS(root);	// Uses timer to terminate

		// Select the best action from the children.
		Move bestAction = moves.get(0);
		double score;
		if(root.isMin(stateMachine, agent)){	// Min case: Only one possible action. Find min action value.
			score = 100.0;
			for(Node child : root.children) {
				double result = child.get_value();
				if (result < score)
					score = result;
			}
		} else {								// Max case: Find best action. Find max action value.
			score = 0;
			for(Node child : root.children) {
				double result = child.get_value();
				if (result > score) {
					score = result;
					bestAction = child.action;
				}
			}
		}

		//printTree(root, 1, 0);
		System.out.println("Num depth charges: " + root.visits);
		System.out.println("State/Action Value: " + score);
		return bestAction;
	}

	@Override
	public void MCTS(Node root) {
		for(int i = 0; i<nThreads; i++){
			MCTSThread mctsThread = new MCTSThread(machines.get(i), agent, timer, root, C);
			completionService.submit(mctsThread);
		}

		try {
			int numDepthCharges = 0;
            for (int i = 0;  i < nThreads; i++) {
                Future<Integer> fmcts = completionService.take();	//take is a blocking method
                numDepthCharges += fmcts.get();
            }
            System.out.println("Number of new simulations " + numDepthCharges);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
	}
}


class RootMultiThreadedMonteCarloTreeSearch extends MonteCarloTreeSearch {
	// Depending on the game could be faster or slower than single threaded version.
	static int nThreads;
	List<StateMachine> machines;
	List<Node> roots;

	// Thread pooling
	ExecutorService executor;
	CompletionService<Integer> completionService;

	RootMultiThreadedMonteCarloTreeSearch(StateMachine sm, Role a, Timer t, List<StateMachine> m) {
		super(sm, a, t);
		System.out.println("RootMultiThreadedMonteCarloTreeSearch");
		machines = m;
		nThreads = machines.size();
		roots = new ArrayList<Node>(nThreads);

		executor = Executors.newFixedThreadPool(nThreads);
		completionService = new ExecutorCompletionService<Integer>(executor);
	}

	Node getRoot(MachineState currentState, Node r) throws MoveDefinitionException {
		if(r == null){
			System.out.println("Creating new game tree root");
			r = new Node(currentState, null, null);
			return r;
		}

		if(metagaming)	return r;	// Return the root that was expanded during metagaming

		for(Node child: r.children) {
			if(child.state.equals(currentState)) {
				r = child;
				r.parent = null;
				return r;
			}
		}
		System.out.println("New root not found in children. Creating new game tree root.");
		r = new Node(currentState, null, null);
		return r;
	}

	private List<Node> getRoots(MachineState currentState) throws MoveDefinitionException{
		if(roots.size() == 0){
			for(int i=0; i<nThreads; i++){
				Node newr = getRoot(currentState, null);
				roots.add(newr);
			}
		}
		else{
			for(int i=0; i<roots.size(); i++){
				Node newr = getRoot(currentState, roots.get(i));
				roots.set(i, newr);
			}
		}
		return roots;
	}

	@Override
	public Move getAction(List<Move> moves, MachineState currentState) throws MoveDefinitionException, TransitionDefinitionException {
		roots = getRoots(currentState);
		if(metagaming) metagaming = false;

		// Search the tree
		MCTS(roots);	// Uses timer to terminate

		List<Node> children = compileResults(roots);

		// Select the best action from the children.
		Move bestAction = moves.get(0);
		double score;
		if(roots.get(0).isMin(stateMachine, agent)){	// Min case: Only one possible action. Find min action value.
			score = 100.0;
			for(Node child : children) {
				double result = child.get_value();
				if (result < score)
					score = result;
			}
		} else {								// Max case: Find best action. Find max action value.
			score = 0;
			for(Node child : children) {
				double result = child.get_value();
				if (result > score) {
					score = result;
					bestAction = child.action;
				}
			}
		}

		System.out.println("State/Action Value: " + score);
		return bestAction;
	}

	@Override
	public void MCTS(List<Node> roots) {
		for(int i = 0; i<nThreads; i++){
			AsyncMCTSThread mctsThread = new AsyncMCTSThread(machines.get(i), agent, timer, roots.get(i), C);
			completionService.submit(mctsThread);
		}

		try {
			int numDepthCharges = 0;
            for (int i = 0;  i < nThreads; i++) {
                Future<Integer> fmcts = completionService.take();	//take is a blocking method
                numDepthCharges += fmcts.get();
            }
            System.out.println("Number of new simulations " + numDepthCharges);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
	}

	public List<Node> compileResults(List<Node> roots){
		Map<MachineState, Node> children = new HashMap<MachineState, Node>();
		for(int i = 0; i<roots.size(); i++){
			if(i == 0){
				for(Node c: roots.get(i).children){
					Node newc = new Node(null, null, null);
					newc.visits = c.visits;
					newc.utility = c.utility;
					newc.action = c.action;
					newc.state = c.state;
					children.put(newc.state, newc);
				}
			}
			else{
				for(Node c: roots.get(i).children){
					Node newc = children.get(c.state);
					newc.visits += c.visits;
					newc.utility += c.utility;
					children.put(c.state, newc);
				}
			}
		}
		return new ArrayList<Node>(children.values());
	}

	@Override
	public void metaGame(MachineState currentState) throws MoveDefinitionException, TransitionDefinitionException{
		roots = getRoots(currentState);
		MCTS(roots);
	}
}