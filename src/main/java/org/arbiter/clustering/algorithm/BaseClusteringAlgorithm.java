/*
 * Copyright 2015 Skymind,Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.arbiter.clustering.algorithm;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import org.apache.commons.lang3.ArrayUtils;
import org.arbiter.clustering.algorithm.iteration.IterationHistory;
import org.arbiter.clustering.algorithm.iteration.IterationInfo;
import org.arbiter.clustering.algorithm.strategy.ClusteringStrategy;
import org.arbiter.clustering.algorithm.strategy.ClusteringStrategyType;
import org.arbiter.clustering.algorithm.strategy.OptimizationStrategy;
import org.arbiter.clustering.cluster.Cluster;
import org.arbiter.clustering.cluster.ClusterSet;
import org.arbiter.clustering.cluster.ClusterUtils;
import org.arbiter.clustering.cluster.Point;
import org.arbiter.clustering.cluster.info.ClusterSetInfo;
import org.arbiter.util.MultiThreadUtils;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * adapted to ndarray matrices
 * 
 * @author Adam Gibson
 * @author Julien Roch
 *
 */
public class BaseClusteringAlgorithm implements ClusteringAlgorithm, Serializable {

	private static final long serialVersionUID	= 338231277453149972L;
	private static final Logger log = LoggerFactory.getLogger(BaseClusteringAlgorithm.class);

	private ClusteringStrategy clusteringStrategy;
	private IterationHistory iterationHistory;
	private int	currentIteration = 0;
	private ClusterSet	clusterSet;
	private List<Point>	initialPoints;
    private transient ExecutorService exec;

	protected BaseClusteringAlgorithm(ClusteringStrategy clusteringStrategy) {
		this.clusteringStrategy = clusteringStrategy;
		this.exec = MultiThreadUtils.newExecutorService();
	}

	public static BaseClusteringAlgorithm setup(ClusteringStrategy clusteringStrategy) {
		return new BaseClusteringAlgorithm(clusteringStrategy);
	}

	public ClusterSet applyTo(List<Point> points) {
		resetState(points);
		initClusters();
		iterations();
		return clusterSet;
	}

	private void resetState(List<Point> points) {
		this.iterationHistory = new IterationHistory();
		this.currentIteration = 0;
		this.clusterSet = null;
		this.initialPoints = points;
	}

	private void iterations() {
		while (!clusteringStrategy.getTerminationCondition().isSatisfied(iterationHistory) || iterationHistory.getMostRecentIterationInfo().isStrategyApplied()) {
			currentIteration++;
			removePoints();
			classifyPoints();
			applyClusteringStrategy();
		}
	}

	protected void classifyPoints() {
		ClusterSetInfo clusterSetInfo = ClusterUtils.classifyPoints(clusterSet, initialPoints, exec);
		ClusterUtils.refreshClustersCenters(clusterSet, clusterSetInfo, exec);
		iterationHistory.getIterationsInfos().put(currentIteration, new IterationInfo(currentIteration, clusterSetInfo));
	}

	protected void initClusters() {

		List<Point> points = new ArrayList<>(initialPoints);

		Random random = new Random();
		clusterSet = new ClusterSet(clusteringStrategy.getDistanceFunction());
		clusterSet.addNewClusterWithCenter(points.remove(random.nextInt(points.size())));
		int initialClusterCount = clusteringStrategy.getInitialClusterCount();
		INDArray dxs = Nd4j.create(points.size());
		dxs.addi(Double.MAX_VALUE);
		
		while (clusterSet.getClusterCount() < initialClusterCount) {
			dxs = ClusterUtils.computeSquareDistancesFromNearestCluster(clusterSet, points, dxs, exec);
			double r = random.nextFloat() * Nd4j.max(dxs.getRow(0)).getDouble(0);
			for (int i = 0; i < dxs.length(); i++) {
				if (dxs.getDouble(i) >= r) {
					clusterSet.addNewClusterWithCenter(points.remove(i));
					dxs = Nd4j.create(ArrayUtils.remove(dxs.data().asDouble(), i));
					break;
				}
			}
		}

		ClusterSetInfo initialClusterSetInfo = ClusterUtils.computeClusterSetInfo(clusterSet);
		iterationHistory.getIterationsInfos().put(currentIteration, new IterationInfo(currentIteration, initialClusterSetInfo));
	}

	protected void applyClusteringStrategy() {
		if (!isStrategyApplicableNow())
			return;

		ClusterSetInfo clusterSetInfo = iterationHistory.getMostRecentClusterSetInfo();
		if (!clusteringStrategy.isAllowEmptyClusters()) {
			int removedCount = removeEmptyClusters(clusterSetInfo);
			if( removedCount>0 ) {
				iterationHistory.getMostRecentIterationInfo().setStrategyApplied(true);
				
				if (clusteringStrategy.isStrategyOfType(ClusteringStrategyType.FIXED_CLUSTER_COUNT) && clusterSet.getClusterCount() < clusteringStrategy.getInitialClusterCount()) {
					int splitCount = ClusterUtils.splitMostSpreadOutClusters(clusterSet, clusterSetInfo, clusteringStrategy.getInitialClusterCount() - clusterSet.getClusterCount(),
							exec);
					if( splitCount>0 )
						iterationHistory.getMostRecentIterationInfo().setStrategyApplied(true);
				}
			}
		}
		if (clusteringStrategy.isStrategyOfType(ClusteringStrategyType.OPTIMIZATION))
			optimize();
	}

	protected void optimize() {
		ClusterSetInfo clusterSetInfo = iterationHistory.getMostRecentClusterSetInfo();
		OptimizationStrategy optimization = (OptimizationStrategy) clusteringStrategy;
		boolean applied = ClusterUtils.applyOptimization(optimization, clusterSet, clusterSetInfo, exec);
		iterationHistory.getMostRecentIterationInfo().setStrategyApplied(applied);
	}

	private boolean isStrategyApplicableNow() {
		return clusteringStrategy.isOptimizationDefined() && iterationHistory.getIterationCount() != 0
				&& clusteringStrategy.isOptimizationApplicableNow(iterationHistory);
	}

	protected int removeEmptyClusters(ClusterSetInfo clusterSetInfo) {
		List<Cluster> removedClusters = clusterSet.removeEmptyClusters();
		clusterSetInfo.removeClusterInfos(removedClusters);
		return removedClusters.size();
	}

	protected void removePoints() {
		clusterSet.removePoints();
	}

}
