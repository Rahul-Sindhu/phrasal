package edu.stanford.nlp.mt.parser;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import edu.stanford.nlp.classify.Dataset;
import edu.stanford.nlp.classify.GeneralDataset;
import edu.stanford.nlp.classify.LinearClassifier;
import edu.stanford.nlp.classify.LinearClassifierFactory;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.BasicDatum;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.Datum;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.mt.parser.Actions.Action;
import edu.stanford.nlp.mt.parser.Actions.ActionType;
import edu.stanford.nlp.parser.Parser;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.OpenAddressCounter;
import edu.stanford.nlp.trees.DependencyScoring;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.trees.TypedDependency;
import edu.stanford.nlp.trees.DependencyScoring.Score;
import edu.stanford.nlp.trees.semgraph.SemanticGraph;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.HashIndex;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.StringUtils;

public class DepDAGParser implements Parser, Serializable {

  private static final long serialVersionUID = -5534972476741917367L;

  private LinearClassifier<ActionType,List<String>> actClassifier;
  private LinearClassifier<GrammaticalRelation,List<String>> labelClassifier;
  private static final boolean VERBOSE = false;
  
//to reduce the total number of features for training, remove features appear less than 3 times
  private static final boolean REDUCE_FEATURES = true;
  
  public Map<Collection<List<String>>, Action> history = new HashMap<Collection<List<String>>, Action>();

  @Override
  public boolean parse(List<? extends HasWord> sentence) {
    return true;  // accept everything for now.
  }

  public static DepDAGParser trainModel(
      List<Structure> rawTrainData) {
    DepDAGParser parser = new DepDAGParser();
    
    // to reduce the total number of features for training, remove features appear less than 3 times
    Counter<List<String>> featureCounter = null;
    // TODO:FIX  if(REDUCE_FEATURES) featureCounter = countFeatures(rawTrainData);

    GeneralDataset<ActionType, List<String>> actTrainData = new Dataset<ActionType, List<String>>();
    GeneralDataset<GrammaticalRelation, List<String>> labelTrainData = new Dataset<GrammaticalRelation, List<String>>(); 
    extractTrainingData(rawTrainData, actTrainData, labelTrainData, featureCounter);

    LinearClassifierFactory<ActionType,List<String>> actFactory = new LinearClassifierFactory<ActionType,List<String>>();
    LinearClassifierFactory<GrammaticalRelation,List<String>> labelFactory = new LinearClassifierFactory<GrammaticalRelation,List<String>>();
    // TODO: check options

    featureCounter = null;
    
    // Build a classifier
    parser.labelClassifier = labelFactory.trainClassifier(labelTrainData);
    parser.actClassifier = actFactory.trainClassifier(actTrainData);
    if(VERBOSE) {
      parser.actClassifier.dump();
      parser.labelClassifier.dump();
    }
    
    return parser;
  }

  private static Counter<List<String>> countFeatures(List<Pair<Structure,List<IndexedWord>>> rawTrainData) {
    Counter<List<String>> counter = new OpenAddressCounter<List<String>>();
    for(Pair<Structure,List<IndexedWord>> datum : rawTrainData) {
      Structure struc = datum.first;
      LinkedList<IndexedWord> sentence = new LinkedList<IndexedWord>(datum.second);
      
      List<Action> actions = struc.getActionTrace();
      for(Action act : actions) {
        if (act.incrQueue) {
          struc.input.push(sentence.removeFirst());
        }
        Datum<ActionType, List<String>> actDatum = extractActFeature(act.action, struc, null);
        Datum<GrammaticalRelation, List<String>> labelDatum = extractLabelFeature(act.relation, act.action, actDatum, struc, null); 

        // only count labelDatum features because it includes all actDatum features.
        for(List<String> feature : labelDatum.asFeatures()) {
          counter.incrementCount(feature);
        }
        Actions.doAction(act, struc);
      }
    }
    return counter;
  }

  private static void extractTrainingData(
      List<Structure> rawTrainData, 
      GeneralDataset<ActionType, List<String>> actTrainData, 
      GeneralDataset<GrammaticalRelation, List<String>> labelTrainData, 
      Counter<List<String>> featureCounter) {

    for(Structure struc : rawTrainData) {
      List<Action> actions = struc.getActionTrace();
      // TODO:FIX struc.resetIndex();
      for(Action act : actions) {
        Datum<ActionType, List<String>> actDatum = extractActFeature(act.action, struc, featureCounter);
        Datum<GrammaticalRelation, List<String>> labelDatum = extractLabelFeature(act.relation, act.action, actDatum, struc, featureCounter);
        if(actDatum.asFeatures().size() > 0) actTrainData.add(actDatum);
        if((act.action==ActionType.LEFT_ARC || act.action==ActionType.RIGHT_ARC) 
            && labelDatum.asFeatures().size() > 0) {
          labelTrainData.add(labelDatum);
        }

        Actions.doAction(act, struc);
      }
    }
  }

  private static Datum<ActionType, List<String>> extractActFeature(ActionType act, Structure s, Counter<List<String>> featureCounter){
    // if act == null, test data
    List<List<String>> features = DAGFeatureExtractor.extractActFeatures(s);
    if(featureCounter!=null) {
      Set<List<String>> rareFeatures = new HashSet<List<String>>(); 
      for(List<String> feature : features) {
        if(featureCounter.getCount(feature) < 3) rareFeatures.add(feature);
      }
      features.removeAll(rareFeatures);
    }
    return new BasicDatum<ActionType, List<String>>(features, act);
  }
  private static Datum<GrammaticalRelation, List<String>> extractLabelFeature(
      GrammaticalRelation rel, ActionType action, 
      Datum<ActionType, List<String>> actDatum, Structure s, 
      Counter<List<String>> featureCounter){
    // if act == null, test data
    List<List<String>> features = DAGFeatureExtractor.extractLabelFeatures(action, actDatum, s);
    if(featureCounter!=null) {
      Set<List<String>> rareFeatures = new HashSet<List<String>>();
      for(List<String> feature : features) {
        if(featureCounter.getCount(feature) < 3) rareFeatures.add(feature);
      }
      features.removeAll(rareFeatures);
    }
    return new BasicDatum<GrammaticalRelation, List<String>>(features, rel);
  }
  
  // for extracting features from test data (no gold Action given)
  private static Datum<ActionType, List<String>> extractActFeature(Structure s){
    return extractActFeature(null, s, null);
  }
  private static Datum<GrammaticalRelation, List<String>> extractLabelFeature(ActionType action, Structure s, Datum<ActionType, List<String>> actDatum){
    return extractLabelFeature(null, action, actDatum, s, null);
  }
  
  
  public SemanticGraph getDependencyGraph(Structure s){    
    Datum<ActionType, List<String>> d;
    while((d=extractActFeature(s))!=null){
      Action nextAction;
      if(s.getStack().size()==0) nextAction = new Action(ActionType.SHIFT); 
      else if(history.containsKey(d.asFeatures())){
        nextAction = history.get(d.asFeatures());
      } else {
        nextAction = new Action(actClassifier.classOf(d));
        if(nextAction.action == ActionType.LEFT_ARC || nextAction.action == ActionType.RIGHT_ARC) {
          nextAction.relation = labelClassifier.classOf(extractLabelFeature(nextAction.action, s, d));
        }
        history.put(d.asFeatures(), nextAction);
      }
      if(s.actionTrace.size() > 0 && s.actionTrace.get(s.actionTrace.size()-1).equals(nextAction)
          && nextAction.relation != null) {
        nextAction = new Action(ActionType.SHIFT);
      }
      Actions.doAction(nextAction, s);
    }
    return s.dependencies;    
  }
  public SemanticGraph getDependencyGraph(List<CoreLabel> sentence){
    Structure s = new Structure(sentence);
    return getDependencyGraph(s);
  }
  
  public static void main(String[] args) throws IOException, ClassNotFoundException{

    boolean doTrain = false;
    boolean doTest = true;
    boolean storeTrainedModel = true;
    
    // temporary code for scorer test
    boolean testScorer = false;
    if(testScorer) {
      testScorer();
      return;
    }
    
    Properties props = StringUtils.argsToProperties(args);
    
    // set logger
    
    String timeStamp = Calendar.getInstance().getTime().toString().replaceAll("\\s", "-");
    Logger logger = Logger.getLogger(DepDAGParser.class.getName());
    
    FileHandler fh;
    try {
      String logFileName = props.getProperty("log", "log.txt");
      logFileName.replace(".txt", "_"+ timeStamp+".txt");
      fh = new FileHandler(logFileName, false);
      logger.addHandler(fh);
      logger.setLevel(Level.FINE);
      fh.setFormatter(new SimpleFormatter());
    } catch (SecurityException e) { 
      System.err.println("ERROR: cannot initialize logger!");
      throw e;
    } catch (IOException e) { 
      System.err.println("ERROR: cannot initialize logger!");
      throw e;
    }
    
    if(props.containsKey("train")) doTrain = true;
    if(props.containsKey("test")) doTest = true;
    
    if(REDUCE_FEATURES) logger.fine("REDUCE_FEATURES on");
    else logger.fine("REDUCE_FEATURES off");

    // temporary for debug

//    String tempTrain = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-dev-2011-01-13.conll";
//    String tempTest = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/small_train.conll";
//    props.put("train", tempTrain);
//    props.put("test", tempTest);
//    String tempTest = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/temp2.conll";
//    String tempTrain = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/temp.conll";
//    props.put("train", tempTrain);
//    props.put("test", tempTest);

    if(doTrain) {
      String trainingFile = props.getProperty("train", "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-train-2011-01-13.conll");

      logger.info("read training data from "+trainingFile + " ...");
      List<Structure> trainData = ActionRecoverer.readTrainingData(trainingFile);

      logger.info("train model...");
      DAGFeatureExtractor.printFeatureFlags(logger);
      Date s1 = new Date();
      DepDAGParser parser = trainModel(trainData);
      logger.info((((new Date()).getTime() - s1.getTime())/ 1000F) + "seconds\n");
      
      if(storeTrainedModel) {
        String defaultStore = "/scr/heeyoung/mt/mtdata/DAGparserModel.ser";
        if(!props.containsKey("storeModel")) logger.info("no option -storeModel : trained model will be stored at "+defaultStore); 
        String trainedModelFile = props.getProperty("storeModel", defaultStore);
        IOUtils.writeObjectToFile(parser, trainedModelFile);
      }
      
      logger.info("training is done");
    }
    
    if(doTest) {
      String testFile = props.getProperty("test", "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-dev-2011-01-13.conll");
//      String defaultLoadModel = "/scr/heeyoung/mtdata/DAGparserModel.reducedFeat_mem5_dataset.ser";
      String defaultLoadModel = "/scr/heeyoung/mt/scr61/DAGparserModel.ser";

      if(!props.containsKey("loadModel")) logger.info("no option -loadModel : trained model will be loaded from "+defaultLoadModel); 
      String trainedModelFile = props.getProperty("loadModel", defaultLoadModel);
      logger.info("load trained model...");

      Date s1 = new Date();
      DepDAGParser parser = IOUtils.readObjectFromFile(trainedModelFile);
      logger.info((((new Date()).getTime() - s1.getTime())/ 1000F) + "seconds\n");
//      if(true) return;
      logger.info("read test data from "+testFile + " ...");
      List<Structure> testData = ActionRecoverer.readTrainingData(testFile);
      
      List<Collection<TypedDependency>> goldDeps = new ArrayList<Collection<TypedDependency>>();
      List<Collection<TypedDependency>> systemDeps = new ArrayList<Collection<TypedDependency>>();
      
      logger.info("testing...");
      int count = 0;
      long elapsedTime = 0;
      for(Structure s : testData){
        count++;
        goldDeps.add(s.getDependencyGraph().typedDependencies());
        // TODO:FIX s.resetIndex();
        Date startTime = new Date();
        SemanticGraph graph = parser.getDependencyGraph(s);
        elapsedTime += (new Date()).getTime() - startTime.getTime();
        systemDeps.add(graph.typedDependencies());
      }
      System.out.println("The number of sentences = "+count);
      System.out.printf("avg time per sentence: %.3f seconds\n", (elapsedTime / (count*1000F)));
      System.out.printf("Total elapsed time: %.3f seconds\n", (elapsedTime / 1000F));
      
      logger.info("scoring...");
      DependencyScoring goldScorer = DependencyScoring.newInstanceStringEquality(goldDeps);
      Score score = goldScorer.score(DependencyScoring.convertStringEquality(systemDeps));
      logger.info(score.toString(false));
      logger.info("done");
      
      // parse sentence. (List<CoreLabel>)
      String sent = "My dog also likes eating sausage.";
      Properties pp = new Properties();
      pp.put("annotators", "tokenize, ssplit, pos, lemma");
      StanfordCoreNLP pipeline = new StanfordCoreNLP(pp);
      Annotation document = new Annotation(sent);
      pipeline.annotate(document);
      List<CoreMap> sentences = document.get(SentencesAnnotation.class);

      List<CoreLabel> l = sentences.get(0).get(TokensAnnotation.class);
      SemanticGraph g = parser.getDependencyGraph(l);
    }
  }

  public static void testScorer() throws IOException {
    String trainingFile = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-dev-2011-01-13.conll";
    String devFile = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-dev-2011-01-13.conll";
    List<Structure> devData = ActionRecoverer.readTrainingData(devFile);
//    List<Structure> trainData = ActionRecoverer.readTrainingData(trainingFile);

    List<Collection<TypedDependency>> goldDeps = new ArrayList<Collection<TypedDependency>>();
    List<Collection<TypedDependency>> systemDeps = new ArrayList<Collection<TypedDependency>>();
    Collection<TypedDependency> temp = new ArrayList<TypedDependency>();

    for(Structure s : devData){
      temp = s.getDependencyGraph().typedDependencies();
      goldDeps.add(s.getDependencyGraph().typedDependencies());
//      systemDeps.add(temp);
      temp = s.getDependencyGraph().typedDependencies();
      systemDeps.add(temp);
    }

    DependencyScoring goldScorer = DependencyScoring.newInstanceStringEquality(goldDeps);
    Score score = goldScorer.score(DependencyScoring.convertStringEquality(systemDeps));
    System.out.println(score.toString(true));
  }
  
}