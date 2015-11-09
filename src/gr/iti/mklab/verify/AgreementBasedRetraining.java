package gr.iti.mklab.verify;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.json.JSONObject;
import weka.attributeSelection.InfoGainAttributeEval;
import weka.attributeSelection.Ranker;
import weka.classifiers.Classifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.unsupervised.attribute.Remove;
import eu.socialsensor.framework.client.dao.impl.MediaItemDAOImpl;
import eu.socialsensor.framework.common.domain.MediaItem;
import gr.iti.mklab.extractfeatures.ElementAnnotation;
import gr.iti.mklab.utils.Vars;
import gr.iti.mklab.verifyutils.AttributeSelectionHandler;
import gr.iti.mklab.verifyutils.DataHandler;
import gr.iti.mklab.verifyutils.VerifHandler;

/**
 * Class for applying the agreement-based retraining method
 * 
 * @author boididou
 *
 */
public class AgreementBasedRetraining {

	static Instances[] trainDatasets = new Instances[2];
	static Instances[] testDatasets = new Instances[2];
	static Instances[] sets = new Instances[2];

	static Double itemScore, userScore;

	/**
	 * initialize the parameters
	 */
	public static void initializeParameters() {
		iter = 0;
		// Bagging.internal = 0;
	}

	/**
	 * Forms the training datasets from the arff files given containing the
	 * features
	 * 
	 * @return Instances[] the trainDatasets for Item and User training
	 */
	public Instances[] getTrainDatasets() {

		Instances trainDatasets[] = new Instances[2];
		try {
			
			DataSource ds = new DataSource(prop.getProperty("TRAININGDATA_ITEM_FEATURES"));
			trainDatasets[0] = new Instances("trainItem", ItemClassifier.getFvAttributes(), 0);
			trainDatasets[0] = ds.getDataSet();
			trainDatasets[0].setClassIndex(trainDatasets[0].numAttributes() - 1);

			DataSource ds1 = new DataSource(prop.getProperty("TRAININGDATA_USER_FEATURES"));
			trainDatasets[1] = new Instances("trainUser", UserClassifier.getFvAttributes(), 0);
			trainDatasets[1] = ds1.getDataSet();
			trainDatasets[1].setClassIndex(trainDatasets[1].numAttributes() - 1);

		} catch (Exception e) {
			System.out.println("Error! Cannot fetch datasets...");
			e.printStackTrace();
		}

		return trainDatasets;
	}

	public Instances[] getTestDatasets() {
		
		try {
			
			BufferedReader br = new BufferedReader (new FileReader(prop.getProperty("TESTINGDATA_JSON")) );
			String obj = br.readLine();

			JSONObject jsonObject = new JSONObject(obj);

			try {
				testDatasets[0] = ItemClassifier.formTestingSet(jsonObject);
				testDatasets[1] = UserClassifier.formTestingSet(jsonObject);
			} catch (Exception e) {
				System.out.println("Error! Cannot fetch datasets...");
				e.printStackTrace();
			}
			
			br.close();
			
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		return testDatasets;
	}

	static int iter = 0;

	/**
	 * Auxiliary recursive method to find the common ids among different set of
	 * Instances
	 * 
	 * @param currentSet
	 *            the set compared on every iteration
	 * @param ids
	 *            List<String> the common ids of the previous iteration
	 * @return List<String> the final list of the common ids
	 */
	public List<String> findCommon(Instances currentSet, List<String> ids) {

		iter++;
		// declare the list of common ids
		List<String> commonIds = new ArrayList<String>();

		// iterate through the set
		// compare with the previous list of ids
		for (Instance inst : currentSet) {
			if (ids.contains(inst.stringValue(0))) {
				commonIds.add(inst.stringValue(0));
			}
		}

		if (iter < testDatasets.length) {
			commonIds = findCommon(testDatasets[iter], commonIds);
		}

		return commonIds;
	}

	/**
	 * Finds the common items among the two testDatasets according to their id
	 * 
	 * @param datasets
	 *            the Item and User testing sets
	 * @return Instances[] the two sets containing the common items
	 */
	public Instances[] findCommonSets(Instances[] datasets) {

		// initialize the Item and User sets that will keep the common Item and
		// User Instances will be found below
		sets[0] = new Instances("Rel1", ItemClassifier.getFvAttributes(), datasets[0].size());
		sets[1] = new Instances("Rel2", UserClassifier.getFvAttributes(), datasets[1].size());

		sets[0].setClassIndex(ItemClassifier.getFvAttributes().size() - 1);
		sets[1].setClassIndex(UserClassifier.getFvAttributes().size() - 1);

		// save the ids of the datasets[0] set...
		List<String> ids = new ArrayList<String>();
		for (int i = 0; i < datasets[0].size(); i++) {
			ids.add(datasets[0].get(i).stringValue(0));
		}
		// ..and call the findCommon to find the common ids among the
		// datasets[0] and datasets[1]
		List<String> commonIds = findCommon(datasets[0], ids);

		// iterate through the commonIds list found exactly before
		// method that makes sure that the order of instances in the two sets
		// will be same
		for (int i = 0; i < commonIds.size(); i++) {
			// search for the instance with each id for the first test set
			for (int j = 0; j < datasets[0].size(); j++) {
				if (commonIds.get(i).equals(datasets[0].get(j).stringValue(0))) {
					sets[0].add(datasets[0].get(j));
				}
			}
			// search for the instance with each id for the second test set
			for (int j = 0; j < datasets[1].size(); j++) {
				if (commonIds.get(i).equals(datasets[1].get(j).stringValue(0))) {
					sets[1].add(datasets[1].get(j));
				}
			}
		}

		int countFake = 0, countReal = 0;
		// count the number of fake and real items the sets[0] contains. sets[1]
		// has the same number of fake and real as the sets[0].
		for (int i = 0; i < sets[0].size(); i++) {
			if (sets[0].classAttribute()
					.value((int) sets[0].get(i).classValue()).equals("fake")) {
				countFake++;
			} else {
				countReal++;
			}
		}

		System.out.println("Common items found " + commonIds.size() + ". Fake "
				+ countFake + ", real " + countReal);
		System.out.println("sets " + sets[0].size() + " " + sets[1].size());

		return sets;
	}

	/**
	 * Function that returns a new instance of the current classifier being used
	 * 
	 * @return Classifier cls
	 */
	public Classifier getCurrentClassifier() {
		RandomForest cls = new RandomForest();
		return cls;
	}

	public static ElementAnnotation getElemAnnotation(Instance inst1, VerificationResult verif1, VerificationResult verif2) {

		ElementAnnotation ela = new ElementAnnotation();
		// set id of the element
		ela.setId(inst1.stringValue(0));
		// set its actual value
		//String actual = sets[0].classAttribute().value((int) inst1.classValue());
		//ela.setActual(actual);
		// set its predicted value (only for the agreed elements)
		// set the agreed value of the element
		if (verif1.getPrediction().equals(verif2.getPrediction())) {
			ela.setPredicted(verif1.getPrediction());
			ela.setConfidenceValue((verif1.getProb() + verif2.getProb()) / 2);
			ela.setAgreed(true);
		} else {
			if (itemScore*verif1.getProb() > userScore*verif2.getProb()) {
				ela.setConfidenceValue(verif1.getProb());
			}
			else {
				ela.setConfidenceValue(verif2.getProb());
			}
			ela.setAgreed(false);
		}
		return ela;
	}

	/**
	 * Classifies the set of items given a set of item and user classifiers
	 * 
	 * @param itemCls
	 *            Classifier[] from the bagging procedure for the Item case
	 * @param userCls
	 *            Classifier[] from the bagging procedure for the User case
	 * @return List<ElementAnnotation> list that contains the elements and their
	 *         annotation details after the classification process(classifiers
	 *         agreed, classifiers disagreed).
	 * @throws Exception
	 */
	public List<ElementAnnotation> classifyItems(Classifier[] itemCls,
			Classifier[] userCls) throws Exception {

		/*
		 * define the Element Annotation list The list holds information about
		 * if the items were classified as agreed or disagreed and details about
		 * the predictions
		 */
		List<ElementAnnotation> listEla = new ArrayList<ElementAnnotation>();

		int instaSize = sets[0].size();

		// applies bagging technique for the item and user case and gets the
		// predictions for the testing instances
		System.out.println("Initial tweet classification with Item features and bagging technique");
		VerificationResult[] itemClsPreds = Bagging.classifyItems(itemCls, Bagging.getTestingSets());
		System.out.println("Initial tweet classification with User features and bagging technique");
		VerificationResult[] userClsPreds = Bagging.classifyItems(userCls, Bagging.getTestingSetsUser());

		Remove rm = new Remove();
		rm.setAttributeIndices("1");

		

		// iterate through the predictions
		for (int i = 0; i < itemClsPreds.length; i++) {

			for (int j = 0; j < userClsPreds.length; j++) {

				//compare ids to find the same item in both arrays
				if (itemClsPreds[i].getId().equals(userClsPreds[j].getId())) {

					Instance inst1 = sets[0].get(i);
					
					// find the details of the ElementAnnotation object
					ElementAnnotation ela = getElemAnnotation(inst1, itemClsPreds[i], userClsPreds[i]);

					listEla.add(ela);
				}
			}
		}

		
	
		
		int counter = 0, counterRightPred = 0;
		int counterFake = 0, counterReal = 0, counterFakeDis = 0, counterRealDis = 0, counterRightFake = 0, counterRightReal = 0;

		/*// iterate through the list of Element Annotation
		for (int i = 0; i < listEla.size(); i++) {

			// agreed case
			if (listEla.get(i).getAgreed()) {
				// count the agreed ones
				counter++;

				// count the agreed fake and real ones
				if (listEla.get(i).getActual().equals("fake")) {
					counterFake++;
				} else {
					counterReal++;
				}

				// count the agreed (fake and real) right-predicted ones
				if (listEla.get(i).getActual()
						.equals(listEla.get(i).getPredicted())) {
					counterRightPred++;
					if (listEla.get(i).getActual().equals("fake")) {
						counterRightFake++;
					} else {
						counterRightReal++;
					}
				}
			}

			// disagreed case
			else {
				// count the disagreed fake and real ones
				if (listEla.get(i).getActual().equals("fake")) {
					counterFakeDis++;
				} else {
					counterRealDis++;
				}
			}
		}

		// print info
		System.out.println("Number of agreed items " + counter + " (fake "
				+ counterFake + ",real " + counterReal + ").");
		System.out.println("Percentage of agreed items " + (double) counter
				/ instaSize * 100);

		// System.out.println("accuracy of the agreed "+" fake "+ (double)
		// counterRightFake/counterRightPred +" real "+(double)
		// counterRightReal/counterRightPred);
		System.out.println("Accuracy of agreed items:");
		System.out.println("Right predicted: " + counterRightPred + " (fake "
				+ counterRightFake + ",real " + counterRightReal + ").");
		System.out.println("Accuracy of the agreed percentage "
				+ (double) counterRightPred / counter * 100);
		System.out.println();
		System.out.println("Number of disagreed items " + (instaSize - counter)
				+ "(fake " + counterFakeDis + ",real " + counterRealDis + ").");
		System.out.println("Percentage of disagreed items "
				+ (double) (instaSize - counter) / instaSize * 100);*/

		return listEla;
	}

	/**
	 * Classifies the list of testing set(disagreed items) with the trained
	 * model of the training set(total of agreed items)
	 * 
	 * @param training
	 *            the set of agreed items
	 * @param testing
	 *            the set of disagreed items
	 * @param pointer
	 *            value of 0 if the Instances are of Item type or 1 if the
	 *            Instances are of User type
	 * @throws Exception
	 */
	public void classifyDisagreedOnAgreed(Instances training,
			Instances testing, int pointer) throws Exception {

		/** classify disagreed building model on the agreed **/

		// define the filtered classifier
		FilteredClassifier fc = new FilteredClassifier();
		RandomForest tree = new RandomForest();
		// remove the id attribute
		Remove rm = new Remove();
		rm.setAttributeIndices("1");
		// set the remove filter
		fc.setFilter(rm);
		// set the the classifier type
		fc.setClassifier(tree);

		// build the model
		try {
			fc.buildClassifier(training);
		} catch (Exception e) {
			e.printStackTrace();
		}

		int counter2 = 0, counterFake = 0, counterReal = 0;

		// iterate through the testing set and predict with the model formed
		// before
		for (int i = 0; i < testing.size(); i++) {
			double pred = fc.classifyInstance(testing.instance(i));
			// System.out.print("ID: " + testing.instance(i).stringValue(0));
			String actual = testing.classAttribute().value(
					(int) testing.instance(i).classValue());
			// System.out.print(", actual: " +
			// testing.classAttribute().value((int)
			// testing.instance(i).classValue()));
			String predicted = testing.classAttribute().value((int) pred);
			// System.out.println(", predicted: " +
			// testing.classAttribute().value((int) pred));

			// compare the actual and the predicted values of each instance
			if (actual.equals(predicted)) {
				counter2++;
				if (actual.equals("fake")) {
					counterFake++;
				} else {
					counterReal++;
				}
			}
		}
		// print info
		System.out.println();
		System.out.println("DISAGREED CLASSIFICATION BASED ON THE AGREED");
		System.out.println("Testing with a training set:");
		System.out.println("Disagreed accuracy " + (double) counter2
				/ testing.size() * 100);
		System.out.println("Fake items predicted right " + counterFake);
		System.out.println("Real items predicted right " + counterReal);

		System.out.println("Testing with bagging:");
		int trainingSize = training.size() / 5;

		// apply bagging with training set the agreed items
		if (pointer == 0) {
			Bagging bg = new Bagging();
			Classifier[] itemCls = bg.createClassifiers(training, testing,
					trainingSize);
			Bagging.classifyItems(itemCls, Bagging.getTestingSets());
		} else {
			Bagging bg = new Bagging();
			Classifier[] userCls = bg.createClassifiersUser(training, testing,
					trainingSize);
			Bagging.classifyItems(userCls, Bagging.getTestingSetsUser());
		}
		/** end of classify disagreed building model on the agreed **/

	}

	/**
	 * Classifies the testing set (disagreed items) based on the training set
	 * (agreed items plus total number of the existing training items)
	 * 
	 * @param training
	 *            set of the agreed items added to the existing training set
	 * @param testing
	 *            set of the disagreed items
	 * @param pointer
	 *            value of 0 if the Instances are of Item type or 1 if the
	 *            Instances are of User type
	 * @throws Exception
	 */
	public void classifyDisagreedOnUpdatedExistingModel(Instances training,
			Instances testing, int pointer) throws Exception {

		// classifier settings
		FilteredClassifier fc = new FilteredClassifier();
		RandomForest tree = new RandomForest();
		Remove rm = new Remove();
		rm.setAttributeIndices("1");

		fc.setFilter(rm);
		fc.setClassifier(tree);

		// get the initial training set
		Instances init_train = trainDatasets[pointer];
		Instances newtraining = new Instances(training);
		// add it to the agreed items
		newtraining.addAll(init_train);

		try {
			fc.buildClassifier(newtraining);
		} catch (Exception e) {
			e.printStackTrace();
		}

		int counter2 = 0, counterFake = 0, counterReal = 0;

		for (int i = 0; i < testing.size(); i++) {
			String actual = testing.classAttribute().value(
					(int) testing.instance(i).classValue());

			if (actual.equals("fake")) {
				counterFake++;
			} else {
				counterReal++;
			}

		}

		counter2 = 0;
		counterFake = 0;
		counterReal = 0;
		for (int i = 0; i < testing.size(); i++) {

			double pred = fc.classifyInstance(testing.instance(i));
			// System.out.print("ID: " + testing.instance(i).stringValue(0));
			String actual = testing.classAttribute().value(
					(int) testing.instance(i).classValue());
			// System.out.print(", actual: " +
			// testing.classAttribute().value((int)
			// testing.instance(i).classValue()));
			String predicted = testing.classAttribute().value((int) pred);
			// System.out.println(", predicted: " +
			// testing.classAttribute().value((int) pred));

			if (actual.equals(predicted)) {
				counter2++;
				if (actual.equals("fake")) {
					counterFake++;
				} else {
					counterReal++;
				}
			}
		}
		System.out.println();
		System.out
				.println("DISAGREED CLASSIFICATION BASED ON THE UPDATED EXISTING MODEL: ");
		System.out.println("Testing with a training set:");
		System.out.println("Fake items predicted right " + counterFake);
		System.out.println("Real items predicted right " + counterReal);
		System.out.println("Disagreed accuracy " + (double) counter2
				/ testing.size() * 100);

		System.out.println("Testing with bagging:");
		int trainingSize = newtraining.size() / 5; // define a random number for
													// bagging set, you can
													// define wich number you
													// want

		if (pointer == 0) {
			Bagging bg = new Bagging();
			Classifier[] itemCls = bg.createClassifiers(newtraining, testing,
					trainingSize);
			Bagging.classifyItems(itemCls, Bagging.getTestingSets());
		} else {
			Bagging bg = new Bagging();
			Classifier[] userCls = bg.createClassifiersUser(newtraining,
					testing, trainingSize);
			Bagging.classifyItems(userCls, Bagging.getTestingSetsUser());
		}

	}

	/**
	 * Classifies the testing set (disagreed items) based on the training set
	 * (random set of equal number of fake and real items of the training set
	 * (=agreed items plus the existing training items))
	 * 
	 * @param training
	 *            the random set of the agreed items plus the existing training
	 *            items
	 * @param testing
	 *            the disagreed items
	 * @param pointer
	 *            value of 0 if the Instances are of Item type or 1 if the
	 *            Instances are of User type
	 * @throws Exception
	 */
	public void classifyDisagreedOnUpdatedExistingModelInstance(
			Instances training, Instances testing, int pointer)
			throws Exception {

		int counter2 = 0, counterFake = 0, counterReal = 0;
		FilteredClassifier fc = new FilteredClassifier();
		RandomForest tree = new RandomForest();
		Remove rm = new Remove();
		rm.setAttributeIndices("1");

		fc.setFilter(rm);
		fc.setClassifier(tree);

		Instances init_train = trainDatasets[pointer];
		Instances newtraining = new Instances(training);
		newtraining.addAll(init_train);

		Collections.shuffle(newtraining, new Random(Bagging.randomVals[4]));

		counterFake = 0;
		counterReal = 0;
		for (int i = 0; i < newtraining.size(); i++) {
			if (newtraining.classAttribute()
					.value((int) newtraining.instance(i).classValue())
					.equals("fake")) {
				counterFake++;
			} else
				counterReal++;
		}

		int min = Math.min(counterFake, counterReal);

		Instances trainingInst = new Instances(newtraining);
		counterFake = 0;
		counterReal = 0;

		for (int i = 0; i < newtraining.size(); i++) {
			if (counterFake < (min)) {
				if (newtraining.classAttribute()
						.value((int) newtraining.get(i).classValue())
						.equals("fake")) {
					counterFake++;
					trainingInst.add(newtraining.get(i));
				}
			}
		}

		for (int i = 0; i < newtraining.size(); i++) {
			if (counterReal < (min)) {
				if (newtraining.classAttribute()
						.value((int) newtraining.get(i).classValue())
						.equals("real")) {
					trainingInst.add(newtraining.get(i));
					counterReal++;
				}
			}
		}

		System.out.println(counterFake + " " + counterReal);
		try {
			fc.buildClassifier(trainingInst);
		} catch (Exception e) {
			e.printStackTrace();
		}

		counter2 = 0;
		counterFake = 0;
		counterReal = 0;
		for (int i = 0; i < testing.size(); i++) {
			double pred = fc.classifyInstance(testing.instance(i));
			// System.out.print("ID: " + testing.instance(i).stringValue(0));
			String actual = testing.classAttribute().value(
					(int) testing.instance(i).classValue());
			// System.out.print(", actual: " +
			// testing.classAttribute().value((int)
			// testing.instance(i).classValue()));
			String predicted = testing.classAttribute().value((int) pred);
			// System.out.println(", predicted: " +
			// testing.classAttribute().value((int) pred));

			if (actual.equals(predicted)) {
				counter2++;
				if (actual.equals("fake")) {
					counterFake++;
				} else {
					counterReal++;
				}
			}
		}
		System.out.println();
		System.out
				.println("DISAGREED CLASSIFICATION BASED ON THE UPDATED EXISTING MODEL INSTANCE: ");
		System.out.println("Fake items predicted right " + counterFake);
		System.out.println("Real items predicted right " + counterReal);
		System.out.println("Disagreed accuracy " + (double) counter2
				/ testing.size() * 100);

	}

	
	
	
	/**
	 * Organizes the disagreed items' classification
	 * 
	 * @param listEla
	 *            the list of ElementAnnotation items
	 * @throws Exception
	 */
	public void classifyDisagreed(List<ElementAnnotation> listEla)
			throws Exception {

		List<String> ids_agreed = new ArrayList<String>();
		List<String> ids_disagreed = new ArrayList<String>();

		// iterate through listEla and separate tweet ids according to their
		// agreed value

		for (int i = 0; i < listEla.size(); i++) {

			if (listEla.get(i).getAgreed()) {
				ids_agreed.add(listEla.get(i).getId());

			} else {
				ids_disagreed.add(listEla.get(i).getId());
			}
		}

		Instances training, testing;
		int pointer = 0;

		// compare the scores of the cross validation in order to set the
		// pointer value(0 for the Item classifer and 1 for the User classifier)
		if (itemScore > userScore) {
			training = new Instances("Rel1", ItemClassifier.getFvAttributes(), ids_agreed.size());
			testing = new Instances("Rel2", ItemClassifier.getFvAttributes(), ids_disagreed.size());
			training.setClassIndex(ItemClassifier.getFvAttributes().size() - 1);
			testing.setClassIndex(ItemClassifier.getFvAttributes().size() - 1);
		} else {
			training = new Instances("Rel1", UserClassifier.getFvAttributes(),
					ids_agreed.size());
			testing = new Instances("Rel2", UserClassifier.getFvAttributes(),
					ids_disagreed.size());
			training.setClassIndex(UserClassifier.getFvAttributes().size() - 1);
			testing.setClassIndex(UserClassifier.getFvAttributes().size() - 1);
			pointer = 1;
		}

		// create the new training and testing sets
		for (int i = 0; i < sets[pointer].size(); i++) {

			Instance inst = sets[pointer].get(i);

			if (ids_agreed.contains(inst.stringValue(0))) {
				training.add(inst);
			} else if (ids_disagreed.contains(inst.stringValue(0))) {
				testing.add(inst);
			}

		}

		// change the class value of the agreed instances. Set the agreed
		// predicted value of the class, not the actual one.
		int counter3 = 0;
		for (int i = 0; i < training.size(); i++) {

			for (int j = 0; j < listEla.size(); j++) {

				if (training.get(i).stringValue(0)
						.equals(listEla.get(j).getId())) {

					String classBefore = training.classAttribute().value(
							(int) training.instance(i).classValue());

					training.get(i)
							.setClassValue(listEla.get(j).getPredicted());

					String classAfter = training.classAttribute().value(
							(int) training.instance(i).classValue());

					if (!classBefore.equals(classAfter)) {
						counter3++;
					}
				}

			}
		}

		classifyDisagreedOnAgreed(training, testing, pointer);
		classifyDisagreedOnUpdatedExistingModel(training, testing, pointer);
		classifyDisagreedOnUpdatedExistingModelInstance(training, testing,
				pointer);
	}

	/**
	 * Classifies the initial testing set by using the specified training set
	 * with equal number of fake and real items
	 * 
	 * @param training
	 *            the specified training set
	 * @param testing
	 *            the specified testing set
	 * @param pointer
	 *            value of 0 if the Instances are of Item type or 1 if the
	 *            Instances are of User type
	 * @throws Exception
	 */
	public void classifyWithRandomTrainInstance(Instances training,
			Instances testing, int pointer) throws Exception {

		int counter2 = 0, counterFake = 0, counterReal = 0;
		FilteredClassifier fc = new FilteredClassifier();
		RandomForest tree = new RandomForest();
		Remove rm = new Remove();
		rm.setAttributeIndices("1");

		fc.setFilter(rm);
		fc.setClassifier(tree);

		// shuffle with a random number equal to the randomVals[3] value
		Collections.shuffle(training, new Random(Bagging.randomVals[3]));

		for (int i = 0; i < training.size(); i++) {
			if (training.classAttribute()
					.value((int) training.instance(i).classValue())
					.equals("fake")) {
				counterFake++;
			} else
				counterReal++;
		}

		int min = Math.min(counterFake, counterReal);

		Instances trainingInst = null;
		if (pointer == 0) {
			trainingInst = new Instances("rel",
					ItemClassifier.getFvAttributes(), 2 * min);
			trainingInst
					.setClassIndex(ItemClassifier.getFvAttributes().size() - 1);
		} else {
			trainingInst = new Instances("rel",
					UserClassifier.getFvAttributes(), 2 * min);
			trainingInst
					.setClassIndex(UserClassifier.getFvAttributes().size() - 1);
		}

		counterFake = 0;
		counterReal = 0;

		for (int i = 0; i < training.size(); i++) {
			if (counterFake < (min)) {
				if (training.classAttribute()
						.value((int) training.get(i).classValue())
						.equals("fake")) {
					counterFake++;
					trainingInst.add(training.get(i));
				}
			}
		}
		for (int i = 0; i < training.size(); i++) {
			if (counterReal < (min)) {
				if (training.classAttribute()
						.value((int) training.get(i).classValue())
						.equals("real")) {
					trainingInst.add(training.get(i));
					counterReal++;
				}
			}
		}

		String currentCase = "ITEM";

		if (pointer == 0) {
			trainingInst = DataHandler.getInstance().getTransformedTraining(
					trainingInst);
			testing = DataHandler.getInstance().getTransformedTesting(testing);
		} else {
			trainingInst = DataHandler.getInstance()
					.getTransformedTrainingUser(trainingInst);
			testing = DataHandler.getInstance().getTransformedTestingUser(
					testing);
			currentCase = "USER";
		}

		try {
			fc.buildClassifier(trainingInst);
		} catch (Exception e) {
			e.printStackTrace();
		}

		counter2 = 0;
		counterFake = 0;
		counterReal = 0;

		for (int i = 0; i < testing.size(); i++) {

			double pred = fc.classifyInstance(testing.instance(i));
			// System.out.print("ID: " + testing.instance(i).stringValue(0));
			String actual = testing.classAttribute().value(
					(int) testing.instance(i).classValue());
			// System.out.print(", actual: " +
			// testing.classAttribute().value((int)
			// testing.instance(i).classValue()));
			String predicted = testing.classAttribute().value((int) pred);
			// System.out.println(", predicted: " +
			// testing.classAttribute().value((int) pred));

			if (actual.equals(predicted)) {
				counter2++;
				if (actual.equals("fake")) {
					counterFake++;
				} else {
					counterReal++;
				}
			}
		}
		System.out.println();
		System.out.println("BASED ON THE RANDOM TRAIN " + currentCase
				+ " INSTANCE:");
		System.out.println("Fake items predicted right " + counterFake);
		System.out.println("Real items predicted right " + counterReal);
		System.out.println("Accuracy " + (double) counter2 / testing.size()
				* 100);
		System.out.println();

	}

	/**
	 * Function that computes the cross-validation score given a specific
	 * dataset Before the computation, applies normalization and linear
	 * regression to the missing values, as well as feature selection if the
	 * 'featSelect' is enabled.
	 * 
	 * @param train
	 *            Instances for which the score is computed
	 * @param pointer
	 *            int with value 0 or 1, depending on the case
	 * @return Double resulted score of the cross-validation
	 */
	public Double getScore(Instances train, int pointer) {

		Double score = 0D; // result

		Remove rm = new Remove(); // remove filter
		rm.setAttributeIndices("1");
		Instances trainNew;
		String currentCase = "ITEM";

		//System.out.println(train);
		
		try {
			if (pointer == 0) {
				trainNew = DataHandler.getInstance().getTransformedTrainingOverall(train);
			} else {
				trainNew = DataHandler.getInstance().getTransformedTrainingUserOverall(train);
				currentCase = "USER";
			}

			Classifier tree = getCurrentClassifier();

			rm.setInputFormat(trainNew);
			Instances trainNew2 = Filter.useFilter(trainNew, rm);

			System.out.println("Cross validation of the training set with the "	+ currentCase + " features");

			score = VerifHandler.getInstance().crossValidate(tree, trainNew2);

		} catch (Exception e) {
			e.printStackTrace();
		}

		return score;
	}

	/**
	 * Verify a set of Items by defining your own training dataset.
	 * 
	 * @throws Exception
	 */
	public static void verifyItems() throws Exception {

		defineProperties();
		
		
		// define a set of random values used to shuffle data during the trials.
		ArrayList<int[]> randomVals = Bagging.initializeRandomVals();

		// create a DoubleVerifyBagging object
		AgreementBasedRetraining dvb = new AgreementBasedRetraining();

		/*
		 * //define the fake and real list of MediaItems for Item and User
		 * classifier case used for training List<MediaItem> trainFake = new
		 * ArrayList<MediaItem>(); MediaItemDAOImpl dao = new
		 * MediaItemDAOImpl("ip", "db", "collection"); trainFake =
		 * dao.getLastMediaItems(0);
		 * 
		 * List<MediaItem> trainReal = new ArrayList<MediaItem>();
		 * MediaItemDAOImpl dao2 = new MediaItemDAOImpl("ip", "db",
		 * "collection"); trainReal = dao2.getLastMediaItems(0);
		 * 
		 * List<List<MediaItem>> list = new ArrayList<List<MediaItem>>();
		 * list.add(trainFake); list.add(trainReal);
		 */

		// call method to create the training sets with the lists given above
		trainDatasets = dvb.getTrainDatasets();

		// define the fake and real list of MediaItems for Item and User
		// classifier case used for testing
		/*
		 * List<MediaItem> testFake = new ArrayList<MediaItem>();
		 * MediaItemDAOImpl dao3 = new MediaItemDAOImpl("ip", "db",
		 * "collection"); testFake = dao3.getLastMediaItems(0);
		 * 
		 * List<MediaItem> testReal = new ArrayList<MediaItem>();
		 * MediaItemDAOImpl dao4 = new MediaItemDAOImpl("ip", "db",
		 * "collection"); testReal = dao4.getLastMediaItems(0);
		 * 
		 * List<List<MediaItem>> list2 = new ArrayList<List<MediaItem>>();
		 * list2.add(testFake); list2.add(testReal);
		 */

		// call method to create the testing sets with the lists given above
		testDatasets = dvb.getTestDatasets();

		/** === END OF STEP 2 === **/
		initializeParameters();
		/** === STEP 3: CLASSIFICATION === **/
		// get the item/user score from cross validation process
		itemScore = dvb.getScore(trainDatasets[0], 0);
		userScore = dvb.getScore(trainDatasets[1], 1);

		// repeat the process several times in order to differentiate the
		// training set (statement "for" is an optional part, you can just run
		// it once)
		for (int i = 0; i < randomVals.size(); i++) {

			// values initialization before each trial execution
			initializeParameters();

			// call method to find the common sets among the testing sets
			/*
			 * Even if we define one set of testing items, feature extraction
			 * may not be performed for some of them, i.e a user's account may
			 * be suspended, so there will be no user features for this one, but
			 * only item features. So, we aim to find those items that co-exist
			 * in the two sets and have both item and user features.
			 */
			sets = dvb.findCommonSets(testDatasets);

			// define the current value of random values
			Bagging.randomVals = randomVals.get(i);

			// classify the testing set using a random instance of the training
			// set
			try {
				// dvb.classifyWithRandomTrainInstance(trainDatasets[0],sets[0],0);
				// dvb.classifyWithRandomTrainInstance(trainDatasets[1],sets[1],1);
			} catch (Exception e) {
				e.printStackTrace();
			}

			// define the set of classifiers for each case
			Classifier[] itemCls;
			Classifier[] userCls;

			try {

				Bagging bg = new Bagging();

				// call method to create the bagging classifiers with the
				// trainDatasets and sets for item and user case
				int trainingSize = trainDatasets[0].size() / 3;
				itemCls = bg.createClassifiers(trainDatasets[0], sets[0], trainingSize);
				trainingSize = trainDatasets[1].size() / 3;
				userCls = bg.createClassifiersUser(trainDatasets[1], sets[1], trainingSize);

				// classify testing sets using the classifiers created above
				List<ElementAnnotation> listEla = dvb.classifyItems(itemCls, userCls);
				// classify disagreed items using the annotation from the
				// previous step
				//dvb.classifyDisagreed(listEla);

			} catch (Exception e) {
				e.printStackTrace();
			}

		}

	}

	static Properties prop = new Properties();

	public static void defineProperties() {

		InputStream input = null;

		try {
			input = new FileInputStream("conf/config.properties");
			prop.load(input);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	public static void main(String[] args) throws Exception {

		verifyItems();

	}

}
