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

package sadl.run.commands;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import sadl.anomalydetecion.AnomalyDetection;
import sadl.constants.Algoname;
import sadl.constants.DetectorMethod;
import sadl.constants.DistanceMethod;
import sadl.constants.FeatureCreatorMethod;
import sadl.constants.ProbabilityAggregationMethod;
import sadl.constants.ScalingMethod;
import sadl.detectors.AnomalyDetector;
import sadl.detectors.VectorDetector;
import sadl.detectors.featureCreators.FeatureCreator;
import sadl.detectors.featureCreators.FullFeatureCreator;
import sadl.detectors.featureCreators.MinimalFeatureCreator;
import sadl.detectors.featureCreators.SmallFeatureCreator;
import sadl.experiments.ExperimentResult;
import sadl.interfaces.ModelLearner;
import sadl.oneclassclassifier.LibSvmClassifier;
import sadl.oneclassclassifier.OneClassClassifier;
import sadl.oneclassclassifier.ThresholdClassifier;
import sadl.oneclassclassifier.clustering.DbScanClassifier;
import sadl.run.factories.LearnerFactory;
import sadl.run.factories.learn.PdttaFactory;
import sadl.run.factories.learn.RTIFactory;

public class SmacRun {

	private enum QualityCriterion {
		F_MEASURE, PRECISION, RECALL, ACCURACY, PHI_COEFFICIENT
	}

	private static final Logger logger = LoggerFactory.getLogger(SmacRun.class);

	/*
	 * ################### SMAC Params ###################
	 */
	// // should be empty. not used, but for parsing smac stuff
	@Parameter()
	private final List<String> mainParams = new ArrayList<>();

	// just for parsing the one silly smac parameter
	@Parameter(names = "-1", hidden = true)
	private Boolean bla;

	@Parameter(names = "-qualityCriterion")
	QualityCriterion qCrit = QualityCriterion.F_MEASURE;

	// @ParametersDelegate
	// private final TrainRun trainRun = new TrainRun(true);
	//
	// @ParametersDelegate
	// private final TestRun testRun = new TestRun(true);

	/*
	 * ################### Tester Params ###################
	 */
	// Detector parameters
	@Parameter(names = "-aggregateSublists", arity = 1)
	boolean aggregateSublists = false;

	@Parameter(names = "-aggregatedTimeThreshold")
	private double aggregatedTimeThreshold;

	@Parameter(names = "-aggregatedEventThreshold")
	private double aggregatedEventThreshold;

	@Parameter(names = "-singleEventThreshold")
	private double singleEventThreshold;

	@Parameter(names = "-singleTimeThreshold")
	private double singleTimeThreshold;

	@Parameter(names = "-probabilityAggregationMethod")
	ProbabilityAggregationMethod aggType = ProbabilityAggregationMethod.NORMALIZED_MULTIPLY;

	@Parameter(names = "-svmNu")
	double svmNu;

	@Parameter(names = "-svmGamma")
	double svmGamma;

	@Parameter(names = "-svmEps")
	double svmEps;

	@Parameter(names = "-svmKernel")
	int svmKernelType;

	@Parameter(names = "-svmDegree")
	int svmDegree;

	@Parameter(names = "-svmProbabilityEstimate")
	int svmProbabilityEstimate;

	@Parameter(names = "-detectorMethod", description = "the anomaly detector method")
	DetectorMethod detectorMethod = DetectorMethod.SVM;

	@Parameter(names = "-featureCreator")
	FeatureCreatorMethod featureCreatorMethod = FeatureCreatorMethod.FULL_FEATURE_CREATOR;

	@Parameter(names = "-scalingMethod")
	ScalingMethod scalingMethod = ScalingMethod.NONE;

	@Parameter(names = "-distanceMetric", description = "Which distance metric to use for DBSCAN")
	DistanceMethod dbScanDistanceMethod = DistanceMethod.EUCLIDIAN;

	@Parameter(names = "-dbScanEps")
	private double dbscan_eps;

	@Parameter(names = "-dbScanN")
	private int dbscan_n;

	private OneClassClassifier classifier;



	@SuppressWarnings("null")
	public void run(JCommander jc) {
		logger.info("Starting new SmacRun with commands={}", jc.getUnknownOptions());

		// TODO Try to use this again
		// final Pair<TimedInput, TimedInput> inputs = IoUtils.readTrainTestFile(inputSeqs);
		// trainRun.trainSeqs = inputs.getFirst();
		// testRun.trainSeqs = inputs.getFirst();
		// testRun.testSeqs = inputs.getSecond();
		//
		// final Model m = trainRun.run(jc);
		// testRun.testModel = m;
		// final ExperimentResult result = testRun.run();

		FeatureCreator featureCreator;
		AnomalyDetector anomalyDetector;
		if (featureCreatorMethod == FeatureCreatorMethod.FULL_FEATURE_CREATOR) {
			featureCreator = new FullFeatureCreator();
		} else if (featureCreatorMethod == FeatureCreatorMethod.SMALL_FEATURE_CREATOR) {
			featureCreator = new SmallFeatureCreator();
		} else if (featureCreatorMethod == FeatureCreatorMethod.MINIMAL_FEATURE_CREATOR) {
			featureCreator = new MinimalFeatureCreator();
		} else {
			featureCreator = null;
		}
		if (detectorMethod == DetectorMethod.SVM) {
			classifier = new LibSvmClassifier(svmProbabilityEstimate, svmGamma, svmNu, svmKernelType, svmEps, svmDegree, scalingMethod);
		} else if (detectorMethod == DetectorMethod.THRESHOLD_AGG_ONLY) {
			classifier = new ThresholdClassifier(aggregatedEventThreshold, aggregatedTimeThreshold);
		} else if (detectorMethod == DetectorMethod.THRESHOLD_ALL) {
			classifier = new ThresholdClassifier(aggregatedEventThreshold, aggregatedTimeThreshold, singleEventThreshold, singleTimeThreshold);
		} else if (detectorMethod == DetectorMethod.DBSCAN) {
			classifier = new DbScanClassifier(dbscan_eps, dbscan_n, dbScanDistanceMethod, scalingMethod);
		} else {
			classifier = null;
		}
		anomalyDetector = new VectorDetector(aggType, featureCreator, classifier, aggregateSublists);

		final Pair<Algoname, Path> params = extractAlgoAndInput();
		final ModelLearner learner = getLearner(params.getLeft(), jc);
		final AnomalyDetection detection = new AnomalyDetection(anomalyDetector, learner);
		ExperimentResult result = null;
		try {
			result = detection.trainTest(params.getRight());
		} catch (final IOException e) {
			logger.error("Error when loading input from file!");
			smacErrorAbort();
		}

		// Can stay the same
		double qVal = 0.0;
		switch (qCrit) {
		case F_MEASURE:
			qVal = result.getFMeasure();
			break;
		case PRECISION:
			qVal = result.getPrecision();
			break;
		case RECALL:
			qVal = result.getRecall();
			break;
		case PHI_COEFFICIENT:
			qVal = result.getPhiCoefficient();
			break;
		case ACCURACY:
			qVal = result.getAccuracy();
			break;
		default:
			logger.error("Quality criterion not found!");
			break;
		}

		logger.info(qCrit.name() + "={}", qVal);
		System.out.println("Result for SMAC: SUCCESS, 0, 0, " + (1 - qVal) + ", 0");

	}

	private Pair<Algoname, Path> extractAlgoAndInput() {

		final Set<String> algoNames = Arrays.stream(Algoname.values()).map(a -> a.name().toLowerCase()).collect(Collectors.toSet());

		Algoname algo = null;
		Path input = null;
		for (final String arg : mainParams) {
			if (algoNames.contains(arg.toLowerCase()) && algo == null) {
				algo = Algoname.getAlgoname(arg);
			} else if (arg.contains("/") && input == null) {
				input = Paths.get(arg);
			}
		}
		if (algo == null) {
			logger.error("Algo not found for mainParams={}!", mainParams);
			smacErrorAbort();
		}
		return Pair.of(algo, input);
	}

	private ModelLearner getLearner(Algoname algoName, JCommander jc) {

		LearnerFactory lf = null;

		switch (algoName) {
		case RTI:
			lf = new RTIFactory();
			break;
		case PDTTA:
			lf = new PdttaFactory();
			break;
			// TODO Add other learning algorithms
		default:
			logger.error("Unknown algo param {}!", algoName);
			smacErrorAbort();
			break;
		}

		final JCommander subjc = new JCommander(lf);
		logger.debug("unknown options array for jcommander={}", Arrays.toString(jc.getUnknownOptions().toArray(new String[0])));
		subjc.parse(jc.getUnknownOptions().toArray(new String[0]));

		@SuppressWarnings("null")
		final ModelLearner ml = lf.create();
		return ml;
	}

	protected void smacErrorAbort() {
		System.out.println("Result for SMAC: CRASHED, 0, 0, 0, 0");
		System.exit(1);
	}

}
