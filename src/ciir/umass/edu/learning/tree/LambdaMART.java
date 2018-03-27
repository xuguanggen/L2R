/*===============================================================================
 * Copyright (c) 2010-2012 University of Massachusetts.  All Rights Reserved.
 *
 * Use of the RankLib package is subject to the terms of the software license set 
 * forth in the LICENSE file included with this software, and also available at
 * http://people.cs.umass.edu/~vdang/ranklib_license.html
 *===============================================================================
 */

package ciir.umass.edu.learning.tree;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import ciir.umass.edu.learning.DataPoint;
import ciir.umass.edu.learning.RankList;
import ciir.umass.edu.learning.Ranker;
import ciir.umass.edu.metric.MetricScorer;
import ciir.umass.edu.utilities.MyThreadPool;
import ciir.umass.edu.utilities.SimpleMath;
import ciir.umass.edu.utilities.MergeSorter;
import ciir.umass.edu.utilities.CDF_Normal;

/**
 * @author vdang
 *
 *  This class implements LambdaMART.
 *  Q. Wu, C.J.C. Burges, K. Svore and J. Gao. Adapting Boosting for Information Retrieval Measures. 
 *  Journal of Information Retrieval, 2007.
 */
public class LambdaMART extends Ranker {
	//Parameters
	public static int nTrees = 1000;//the number of trees
	public static float learningRate = 0.1F;//or shrinkage
	public static int nThreshold = 256;
	public static int nRoundToStopEarly = 100;//If no performance gain on the *VALIDATION* data is observed in #rounds, stop the training process right away. 
	public static int nTreeLeaves = 10;
	public static int minLeafSupport = 1;
    // add for sampling
    public static float col_sample = 1.0f;
    public static float row_sample = 1.0f;
	public static double alpha=0.0;
    public static int risk_type = 0;
	//for debugging
	public static int gcCycle = 100;
	
	//Local variables
	protected float[][] thresholds = null;
	protected Ensemble ensemble = null;
	protected double[] modelScores = null;//on training data
    protected double[] baselineEval = null;  //liuhui
    protected double[] modelEval = null;   //liuhui
    protected double currPairedSTD = 0.0d;  //liuhui
	protected double[][] modelScoresOnValidation = null;
	protected int bestModelOnValidation = Integer.MAX_VALUE-2;
	
	//Training instances prepared for MART
	protected DataPoint[] martSamples = null;//Need initializing only once
	protected int[][] sortedIdx = null;//sorted list of samples in @martSamples by each feature -- Need initializing only once 
	protected FeatureHistogram hist = null;
	protected double[] pseudoResponses = null;//different for each iteration
	protected double[] weights = null;//different for each iteration
    private double[] sigmoidCache;	 //liuhui
    private double minScore; //liuhui
    private double maxScore; //liuhui
    private double sigmoidBinWidth; //liuhui
    public int sigmoidBins = 1000000; //liuhui    
     
	public LambdaMART()
	{		
	}
	public LambdaMART(List<RankList> samples, int[] features, MetricScorer scorer)
	{
		super(samples, features, scorer);
	}
	
	public void init()
	{
		PRINT("Initializing... ");		
		//initialize samples for MART
		int dpCount = 0;
        baselineEval = new double[samples.size()];
        modelEval = new double[samples.size()];
		for(int i=0;i<samples.size();i++)
		{
			RankList rl = samples.get(i);
            baselineEval[i]= scorer.score(rl);
            modelEval[i]= scorer.score(rl);
			dpCount += rl.size();
		}
		int current = 0;
		martSamples = new DataPoint[dpCount];
		modelScores = new double[dpCount];
		pseudoResponses = new double[dpCount];
		weights = new double[dpCount];
		for(int i=0;i<samples.size();i++)
		{
			RankList rl = samples.get(i);
			for(int j=0;j<rl.size();j++)
			{
				martSamples[current+j] = rl.get(j);
				modelScores[current+j] = 0.0F;
				pseudoResponses[current+j] = 0.0F;
				weights[current+j] = 0;
			}
			current += rl.size();
		}			
		
		//sort (MART) samples by each feature so that we can quickly retrieve a sorted list of samples by any feature later on.
		sortedIdx = new int[features.length][];
		MyThreadPool p = MyThreadPool.getInstance();
		if(p.size() == 1)//single-thread
			sortSamplesByFeature(0, features.length-1);
		else//multi-thread
		{
			int[] partition = p.partition(features.length);
			for(int i=0;i<partition.length-1;i++)
				p.execute(new SortWorker(this, partition[i], partition[i+1]-1));
			p.await();
		}
		
		//Create a table of candidate thresholds (for each feature). Later on, we will select the best tree split from these candidates 
		thresholds = new float[features.length][];
		for(int f=0;f<features.length;f++)
		{
			//For this feature, keep track of the list of unique values and the max/min 
			List<Float> values = new ArrayList<Float>();
			float fmax = Float.NEGATIVE_INFINITY;
			float fmin = Float.MAX_VALUE;
			for(int i=0;i<martSamples.length;i++)
			{
				int k = sortedIdx[f][i];//get samples sorted with respect to this feature
				float fv = martSamples[k].getFeatureValue(features[f]);
				values.add(fv);
				if(fmax < fv)
					fmax = fv;
				if(fmin > fv)
					fmin = fv;
				//skip all samples with the same feature value
				int j=i+1;
				while(j < martSamples.length)
				{
					if(martSamples[sortedIdx[f][j]].getFeatureValue(features[f]) > fv)
						break;
					j++;
				}
				i = j-1;//[i, j] gives the range of samples with the same feature value
			}
			
			if(values.size() <= nThreshold || nThreshold == -1)
			{
				thresholds[f] = new float[values.size()+1];
				for(int i=0;i<values.size();i++)
					thresholds[f][i] = values.get(i);
				thresholds[f][values.size()] = Float.MAX_VALUE;
			}
			else
			{
				float step = (Math.abs(fmax - fmin))/nThreshold;
				thresholds[f] = new float[nThreshold+1];
				thresholds[f][0] = fmin;
				for(int j=1;j<nThreshold;j++)
					thresholds[f][j] = thresholds[f][j-1] + step;
				thresholds[f][nThreshold] = Float.MAX_VALUE;
			}
		}
		
		if(validationSamples != null)
		{
			modelScoresOnValidation = new double[validationSamples.size()][];
			for(int i=0;i<validationSamples.size();i++)
			{
				modelScoresOnValidation[i] = new double[validationSamples.get(i).size()];
				Arrays.fill(modelScoresOnValidation[i], 0);
			}
		}
         initSigmoidCache();		
  		//compute the feature histogram (this is used to speed up the procedure of finding the best tree split later on)
		hist = new FeatureHistogram();
        FeatureHistogram.samplingRate = LambdaMART.col_sample;
		hist.construct(martSamples, pseudoResponses, sortedIdx, features, thresholds);
		//we no longer need the sorted indexes of samples
		sortedIdx = null;
		
		System.gc();
		PRINTLN("[Done]");
	}
    private void initSigmoidCache(){   //liuhui

        minScore = -50.0/LambdaMART.learningRate;
        maxScore = -minScore;
        sigmoidCache = new double[sigmoidBins];
        sigmoidBinWidth = (maxScore - minScore) / sigmoidBins;
        double score;
        for (int i = 0; i < sigmoidBins; i++) {
             score = minScore + i * sigmoidBinWidth;
             if (score > 0.0) {
                sigmoidCache[i] = 1.0 - 1.0 / (1.0 + Math.exp(-LambdaMART.learningRate * score));
             } else {
                sigmoidCache[i] = 1.0 / (1.0 + Math.exp(LambdaMART.learningRate * score));
             } 
        }  
          
    }
	public void learn()
	{
		ensemble = new Ensemble();
		
		PRINTLN("---------------------------------");
		PRINTLN("Training starts...");
		PRINTLN("---------------------------------");
		PRINTLN(new int[]{7, 9, 9}, new String[]{"#iter", scorer.name()+"-T", scorer.name()+"-V"});
		PRINTLN("---------------------------------");		
		
		//Start the gradient boosting process
		for(int m=0; m<nTrees; m++)
		{
			PRINT(new int[]{7}, new String[]{(m+1)+""});
			
			//Compute lambdas (which act as the "pseudo responses")
			//Create training instances for MART:
			//  - Each document is a training sample
			//	- The lambda for this document serves as its training label
            if(m==0)
            {
                double[] params = getEstimates(baselineEval, modelEval,samples.size());
                currPairedSTD = Math.sqrt(params[1]);
            }
			computePseudoResponses();
			
            // row sampling here, randomly make pseudoResponses to zero
            // System.out.println("do row sample: " + LambdaMART.row_sample);
            // System.out.println("martSamples length: " + martSamples.length);
            Random rng = new Random(m + 1234);
            hist.rng = rng;
            for(int i = 0; i < martSamples.length; ++ i) {
                if(rng.nextFloat() > LambdaMART.row_sample) {
                    pseudoResponses[i] = 0.0f;
                }
            }

			//update the histogram with these training labels (the feature histogram will be used to find the best tree split)
			hist.update(pseudoResponses);
		
			//Fit a regression tree			
			RegressionTree rt = new RegressionTree(nTreeLeaves, martSamples, pseudoResponses, hist, minLeafSupport);
			rt.fit();
			
			//Add this tree to the ensemble (our model)
			ensemble.add(rt, learningRate);

			//update the outputs of the tree (with gamma computed using the Newton-Raphson method) 
			updateTreeOutput(rt);
			
			//Update the model's outputs on all training samples
			List<Split> leaves = rt.leaves();
			for(int i=0;i<leaves.size();i++)
			{
				Split s = leaves.get(i);
				int[] idx = s.getSamples();
				for(int j=0;j<idx.length;j++)
					modelScores[idx[j]] += learningRate * s.getOutput();
			}

			//clear references to data that is no longer used
			rt.clearSamples();
			
			//beg the garbage collector to work...
			if(m % gcCycle == 0)
				System.gc();//this call is expensive. We shouldn't do it too often.

			//Evaluate the current model
			scoreOnTrainingData = computeModelScoreOnTraining();
			//**** NOTE ****
			//The above function to evaluate the current model on the training data is equivalent to a single call:
			//
			//		scoreOnTrainingData = scorer.score(rank(samples);
			//
			//However, this function is more efficient since it uses the cached outputs of the model (as opposed to re-evaluating the model 
			//on the entire training set).
			
			PRINT(new int[]{9}, new String[]{SimpleMath.round(scoreOnTrainingData, 4) + ""});			
		    finalscoreTraining = scoreOnTrainingData;	
			//Evaluate the current model on the validation data (if available)
			if(validationSamples != null)
			{
				//Update the model's scores on all validation samples
				for(int i=0;i<modelScoresOnValidation.length;i++)
					for(int j=0;j<modelScoresOnValidation[i].length;j++)
						modelScoresOnValidation[i][j] += learningRate * rt.eval(validationSamples.get(i).get(j));
				
				//again, equivalent to scoreOnValidation=scorer.score(rank(validationSamples)), but more efficient since we use the cached models' outputs
				double score = computeModelScoreOnValidation();
				
				PRINT(new int[]{9}, new String[]{SimpleMath.round(score, 4) + ""});
				if(score > bestScoreOnValidationData)
				{
					bestScoreOnValidationData = score;
					bestModelOnValidation = ensemble.treeCount()-1;
				}
                finalscoreValidation = score;
			}
			
			PRINTLN("");
			
			//Should we stop early?
			if(m - bestModelOnValidation > nRoundToStopEarly)
				break;
		}
		
		//Rollback to the best model observed on the validation data
		while(ensemble.treeCount() > bestModelOnValidation+1)
			ensemble.remove(ensemble.treeCount()-1);
		
		//Finishing up
		scoreOnTrainingData = scorer.score(rank(samples));
		PRINTLN("---------------------------------");
		PRINTLN("Finished sucessfully.");
		PRINTLN(scorer.name() + " besttree: " + bestModelOnValidation +" bestleaf: "+ nTreeLeaves +" on training data: " + SimpleMath.round(scoreOnTrainingData, 4));
        PRINTLN(scorer.name() + " finaltree: "+ nTrees +" bestleaf: "+ nTreeLeaves + " on training data: " + SimpleMath.round(finalscoreTraining,4));

        //PRINTLN("Finished sucessfully.");
		if(validationSamples != null)
		{
			bestScoreOnValidationData = scorer.score(rank(validationSamples));
			PRINTLN(scorer.name() + " besttree: "+ bestModelOnValidation +" bestleaf: "+ nTreeLeaves + " on validation data: " + SimpleMath.round(bestScoreOnValidationData, 4));
            PRINTLN(scorer.name() + " finaltree: "+ nTrees +" bestleaf: "+ nTreeLeaves + " on validation data: " + SimpleMath.round(finalscoreValidation,4));
		}
		PRINTLN("---------------------------------");
	}
	public double eval(DataPoint dp)
	{
		return ensemble.eval(dp);
	}	
	public Ranker clone()
	{
		return new LambdaMART();
	}
	public String toString()
	{
		return ensemble.toString();
	}
	public String model()
	{
		String output = "## " + name() + "\n";
		output += "## No. of trees = " + nTrees + "\n";
		output += "## No. of leaves = " + nTreeLeaves + "\n";
		output += "## No. of threshold candidates = " + nThreshold + "\n";
		output += "## Learning rate = " + learningRate + "\n";
		output += "## Stop early = " + nRoundToStopEarly + "\n";
		output += "\n";
		output += toString();
		return output;
	}
	public void load(String fn)
	{
		try {
			String content = "";
			String model = "";
			BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(fn), "ASCII"));
			while((content = in.readLine()) != null)
			{
				content = content.trim();
				if(content.length() == 0)
					continue;
				if(content.indexOf("##")==0)
					continue;
				//actual model component
				model += content;
			}
			in.close();
			//load the ensemble
			ensemble = new Ensemble(model);
			features = ensemble.getFeatures();
		}
		catch(Exception ex)
		{
			System.out.println("Error in LambdaMART::load(): " + ex.toString());
		}
	}
	public void printParameters()
	{
		PRINTLN("No. of trees: " + nTrees);
		PRINTLN("No. of leaves: " + nTreeLeaves);
		PRINTLN("No. of threshold candidates: " + nThreshold);
		PRINTLN("Min leaf support: " + minLeafSupport);
		PRINTLN("Learning rate: " + learningRate);
		PRINTLN("Stop early: " + nRoundToStopEarly + " rounds without performance gain on validation data");	
        PRINTLN("Alpha of risk: " + alpha); 
        PRINTLN("Type of risk: " + risk_type);   
	}	
	public String name()
	{
		return "LambdaMART";
	}
	public Ensemble getEnsemble()
	{
		return ensemble;
	}
     
	protected void computePseudoResponses()
	{
		Arrays.fill(pseudoResponses, 0F);
		Arrays.fill(weights, 0);
		MyThreadPool p = MyThreadPool.getInstance();
		if(p.size() == 1)//single-thread
			computePseudoResponses(0, samples.size()-1, 0);
		else //multi-threading
		{
			List<LambdaComputationWorker> workers = new ArrayList<LambdaMART.LambdaComputationWorker>();
			//divide the entire dataset into chunks of equal size for each worker thread
			int[] partition = p.partition(samples.size());
			int current = 0;
			for(int i=0;i<partition.length-1;i++)
			{
				//execute the worker
				LambdaComputationWorker wk = new LambdaComputationWorker(this, partition[i], partition[i+1]-1, current); 
				workers.add(wk);//keep it so we can get back results from it later on
				p.execute(wk);
				
				if(i < partition.length-2)
					for(int j=partition[i]; j<=partition[i+1]-1;j++)
						current += samples.get(j).size();
			}
			
			//wait for all workers to complete before we move on to the next stage
			p.await();
		}
	}
    public static double[] getEstimates(final double []baselineEval, final double[] modelEval, double c)
    {
         double sum = 0D;
         double SSQR = 0D;
         double d_i = 0D;

        for(int i=0; i < c; i++)
        {
             if (modelEval[i] > baselineEval[i])
                 //d_i = modelEval[i] - baselineEval[i];
                 d_i = -baselineEval[i];
             else
                //d_i = (1 + alpha) * (modelEval[i] - baselineEval[i]);
                d_i = (1 + alpha) *(-baselineEval[i]);
            sum += d_i;
            SSQR += d_i * d_i;
         }

        final double URisk = sum /c;
        final double SQRS = sum * sum;
        final double pairedVar = SSQR == SQRS ? 0 : ( SSQR - (SQRS / c) ) / (c-1);
        return new double[] {URisk, pairedVar};
   }
    protected double getDelta_FARO(int queryIndex,double change,int rank_i,int rank_j,float label_i,float label_j){
            final double M_m = modelEval[queryIndex];
            final double M_b = baselineEval[queryIndex];
            double d_i = M_m - M_b;
            double beta = alpha;
            if(currPairedSTD != 0){
                final double TRisk = d_i / currPairedSTD;
                beta = (1 - CDF_Normal.normp(TRisk)) * alpha;
            }
             final double delta_T = (1 + beta) * change;
             return delta_T;

    }

    protected double getDelta_SARO(int queryIndex,double change,int rank_i,int rank_j,float label_i,float label_j){
            final double M_m = modelEval[queryIndex];
            final double M_b = baselineEval[queryIndex];
            double d_i = M_m - M_b;
            double beta = alpha;
            if(currPairedSTD != 0){
                final double TRisk = d_i / currPairedSTD;
                beta = (1 - CDF_Normal.normp(TRisk)) * alpha;
            }
            final double delta_T;
            if (M_m <=  M_b){
                if (label_i > label_j && rank_i< rank_j)
                {
                    delta_T = (1.0d + beta) * change;
                }
                else{
                    if (M_b > M_m + change)
                    {
                        delta_T = (1.0d + beta) * change;
                    }
                    else{
                        delta_T = beta * (M_b - M_m) + change;
                    }
                    
                }
            }
            else{
                    if( label_i > label_j && rank_i < rank_j )
                    {

                        if (M_b > M_m - Math.abs(change))
                        {
                                delta_T = beta * (M_m - M_b) - (1 + beta) * Math.abs(change);
                        }
                        else
                        {
                           
                        delta_T = change;
                        }
                    }
                    else{

                        delta_T = change;

                    }

            }       
                  
            return delta_T;

    }
    protected double getDelta(int queryIndex,double change,int rank_i,int rank_j,float label_i,float label_j){
            final double M_m = modelEval[queryIndex];
            final double M_b = baselineEval[queryIndex];
            assert label_i >= label_j;
            if (rank_i > rank_j)
            {
                 assert change >= 0 : "rank_i=" + rank_i + " rank_j="+rank_j + " delta_M="+change;
            }
            if (rank_i < rank_j)
            {    
                assert change <= 0 : "rank_i=" + rank_i + " rank_j="+rank_j + " delta_M="+change;
            }
            final double delta_T;
            if (M_m <=  M_b){
                if (label_i > label_j && rank_i< rank_j)
                {
                      assert change < 0;
                      delta_T = (1.0d + alpha) * change;
                      assert delta_T < 0 : "M_b="+M_b+" M_m="+M_m + " delta_M=" + change + " => delta_T=" + delta_T ;
                 }
                else
                {
                      assert label_i > label_j && rank_i > rank_j : "M_b="+M_b+" M_m="+M_m + " delta_M=" + change ;
                      assert change >= 0 : "M_b="+M_b+" M_m="+M_m + " delta_M=" + change
                                    + " label_i="+label_i+" label_j="+label_j+" rank_i="+rank_i+" rank_j="+rank_j;
                      if (M_b > M_m + change)
                      {
                               delta_T = (1.0d + alpha) * change;
                      }
                      else
                      {
                           assert M_b <= M_m +  change  : "M_b="+M_b+" M_m="+M_m + " delta_M=" + change ;
                           delta_T = alpha * (M_b - M_m) + change;
                     }
                           assert delta_T > 0 : "M_b="+M_b+" M_m="+M_m + " delta_M=" + change
                                       + " rel_i="+label_i+" rel_j="+label_j+" rank_i="+rank_i+" rank_j="+rank_j+"  => delta_T=" + delta_T ;
                 }
           }
           else{
                assert M_m > M_b : "M_b="+M_b+" M_m="+M_m + " delta_M=" + change;
                   if( label_i > label_j && rank_i < rank_j )
                   {
                     assert change <= 0 : "rank_i=" + rank_i + " rank_j="+rank_j + " delta_M="+change;
                     if (M_b > M_m - Math.abs(change))
                     {
                      delta_T = alpha * (M_m - M_b) - (1 + alpha) * Math.abs(change);
                     }
                     else
                     {
                      assert M_b <= M_m - Math.abs(change) : "M_b="+M_b+" M_m="+M_m + " delta_M=" + change ;
                      delta_T = change;
                     }
                      assert delta_T < 0 : "M_b="+M_b+" M_m="+M_m + " delta_M=" + change
                                 + " rel_i="+label_i+" rel_j="+label_j+" rank_i="+rank_i+" rank_j="+rank_j + " => delta_T=" + delta_T ;
                   }
                   else
                   {
                       assert label_i > label_j && rank_i > rank_j;
                       delta_T = change;
                       assert delta_T > 0;
                   }

           }

           return delta_T;
      }

	protected void computePseudoResponses(int start, int end, int current)
	{
        //double[] params = getEstimates(baselineEval, modelEval,end-start+1);
        //currPairedSTD = Math.sqrt(params[1]);
        double scoreDiff;
        double rho;
		int cutoff = scorer.getK();
		//compute the lambda for each document (a.k.a "pseudo response")
		for(int i=start;i<=end;i++)
		{
			RankList orig = samples.get(i);			
			int[] idx = MergeSorter.sort(modelScores, current, current+orig.size()-1, false);
			RankList rl = new RankList(orig, idx, current);
			double[][] changes = scorer.swapChange(rl);
			//NOTE: j, k are indices in the sorted (by modelScore) list, not the original
			// ==> need to map back with idx[j] and idx[k] 
			for(int j=0;j<rl.size();j++)
			{
				DataPoint p1 = rl.get(j);
				int mj = idx[j];
				for(int k=0;k<rl.size();k++)
				{
					if(j > cutoff && k > cutoff)//swaping these pair won't result in any change in target measures since they're below the cut-off point
						break;
					DataPoint p2 = rl.get(k);
					int mk = idx[k];
					if(p1.getLabel() > p2.getLabel())
					{
                        /*
                        scoreDiff = modelScores[mj] - modelScores[mk];  //liuhui
                        if (scoreDiff <= minScore) {
                            rho = sigmoidCache[0];
                        }else if (scoreDiff >= maxScore) {
                            rho = sigmoidCache[sigmoidCache.length - 1];
                        } else {
                            rho = sigmoidCache[(int) ((scoreDiff - minScore) / sigmoidBinWidth)];
                        }
                        */
                        double deltaNDCG = 0.0;
						double change = changes[j][k];
                        if(risk_type == 0)
                           deltaNDCG = Math.abs(getDelta(i,change,j,k,p1.getLabel(),p2.getLabel()));
                        else if (risk_type == 1)
                           deltaNDCG = Math.abs(getDelta_SARO(i,change,j,k,p1.getLabel(),p2.getLabel()));
                        else if(risk_type == 2)
                           deltaNDCG = Math.abs(getDelta_FARO(i,change,j,k,p1.getLabel(),p2.getLabel()));
						if(deltaNDCG > 0)
						{
							 rho = 1.0 / (1 + Math.exp(modelScores[mj] - modelScores[mk]));
							double lambda = rho * deltaNDCG;
							pseudoResponses[mj] += lambda;
							pseudoResponses[mk] -= lambda;
							double delta = rho * (1.0 - rho) * deltaNDCG;
							weights[mj] += delta;
							weights[mk] += delta;
						}
					}
				}
			}
			current += orig.size();
		}
	}
	protected void updateTreeOutput(RegressionTree rt)
	{
		List<Split> leaves = rt.leaves();
		for(int i=0;i<leaves.size();i++)
		{
			float s1 = 0F;
			float s2 = 0F;
			Split s = leaves.get(i);
			int[] idx = s.getSamples();
			for(int j=0;j<idx.length;j++)
			{
				int k = idx[j];
				s1 += pseudoResponses[k];
				s2 += weights[k];
			}
			if(s2 == 0)
				s.setOutput(0);
			else
				s.setOutput(s1/s2);
		}
	}
	protected int[] sortSamplesByFeature(DataPoint[] samples, int fid)
	{
		double[] score = new double[samples.length];
		for(int i=0;i<samples.length;i++)
			score[i] = samples[i].getFeatureValue(fid);
		int[] idx = MergeSorter.sort(score, true); 
		return idx;
	}
	/**
	 * This function is equivalent to the inherited function rank(...), but it uses the cached model's outputs instead of computing them from scratch.
	 * @param rankListIndex
	 * @param current
	 * @return
	 */
	protected RankList rank(int rankListIndex, int current)
	{
		RankList orig = samples.get(rankListIndex);	
		double[] scores = new double[orig.size()];
		for(int i=0;i<scores.length;i++)
			scores[i] = modelScores[current+i];
		int[] idx = MergeSorter.sort(scores, false);
		return new RankList(orig, idx);
	}
	protected float computeModelScoreOnTraining() 
	{
		/*float s = 0;
		int current = 0;	
		MyThreadPool p = MyThreadPool.getInstance();
		if(p.size() == 1)//single-thread
			s = computeModelScoreOnTraining(0, samples.size()-1, current);
		else
		{
			List<Worker> workers = new ArrayList<Worker>();
			//divide the entire dataset into chunks of equal size for each worker thread
			int[] partition = p.partition(samples.size());
			for(int i=0;i<partition.length-1;i++)
			{
				//execute the worker
				Worker wk = new Worker(this, partition[i], partition[i+1]-1, current);
				workers.add(wk);//keep it so we can get back results from it later on
				p.execute(wk);
				
				if(i < partition.length-2)
					for(int j=partition[i]; j<=partition[i+1]-1;j++)
						current += samples.get(j).size();
			}		
			//wait for all workers to complete before we move on to the next stage
			p.await();
			for(int i=0;i<workers.size();i++)
				s += workers.get(i).score;
		}*/
		float s = computeModelScoreOnTraining(0, samples.size()-1, 0);
		s = s / samples.size();
		return s;
	}
	protected float computeModelScoreOnTraining(int start, int end, int current) 
	{
		float s = 0;
		int c = current;
        
		for(int i=start;i<=end;i++)
		{
            modelEval[i] = scorer.score(rank(i, c)); 
			s += scorer.score(rank(i, c));
			c += samples.get(i).size();
		}
		return s;
	}
	protected float computeModelScoreOnValidation() 
	{
		/*float score = 0;
		MyThreadPool p = MyThreadPool.getInstance();
		if(p.size() == 1)//single-thread
			score = computeModelScoreOnValidation(0, validationSamples.size()-1);
		else
		{
			List<Worker> workers = new ArrayList<Worker>();
			//divide the entire dataset into chunks of equal size for each worker thread
			int[] partition = p.partition(validationSamples.size());
			for(int i=0;i<partition.length-1;i++)
			{
				//execute the worker
				Worker wk = new Worker(this, partition[i], partition[i+1]-1);
				workers.add(wk);//keep it so we can get back results from it later on
				p.execute(wk);
			}		
			//wait for all workers to complete before we move on to the next stage
			p.await();
			for(int i=0;i<workers.size();i++)
				score += workers.get(i).score;
		}*/
		float score = computeModelScoreOnValidation(0, validationSamples.size()-1);
		return score/validationSamples.size();
	}
	protected float computeModelScoreOnValidation(int start, int end) 
	{
		float score = 0;
		for(int i=start;i<=end;i++)
		{
			int[] idx = MergeSorter.sort(modelScoresOnValidation[i], false);
			score += scorer.score(new RankList(validationSamples.get(i), idx));
		}
		return score;
	}
	
	protected void sortSamplesByFeature(int fStart, int fEnd)
	{
		for(int i=fStart;i<=fEnd; i++)
			sortedIdx[i] = sortSamplesByFeature(martSamples, features[i]);
	}

	//For multi-threading processing
	class SortWorker implements Runnable {
		LambdaMART ranker = null;
		int start = -1;
		int end = -1;
		SortWorker(LambdaMART ranker, int start, int end)
		{
			this.ranker = ranker;
			this.start = start;
			this.end = end;
		}		
		public void run()
		{
			ranker.sortSamplesByFeature(start, end);
		}
	}
	class LambdaComputationWorker implements Runnable {
		LambdaMART ranker = null;
		int rlStart = -1;
		int rlEnd = -1;
		int martStart = -1;
		LambdaComputationWorker(LambdaMART ranker, int rlStart, int rlEnd, int martStart)
		{
			this.ranker = ranker;
			this.rlStart = rlStart;
			this.rlEnd = rlEnd;
			this.martStart = martStart;
		}		
		public void run()
		{
			ranker.computePseudoResponses(rlStart, rlEnd, martStart);
		}
	}
	class Worker implements Runnable {
		LambdaMART ranker = null;
		int rlStart = -1;
		int rlEnd = -1;
		int martStart = -1;
		int type = -1;
		
		//compute score on validation
		float score = 0;
		
		Worker(LambdaMART ranker, int rlStart, int rlEnd)
		{
			type = 3;
			this.ranker = ranker;
			this.rlStart = rlStart;
			this.rlEnd = rlEnd;
		}
		Worker(LambdaMART ranker, int rlStart, int rlEnd, int martStart)
		{
			type = 4;
			this.ranker = ranker;
			this.rlStart = rlStart;
			this.rlEnd = rlEnd;
			this.martStart = martStart;
		}
		public void run()
		{
			if(type == 4)
				score = ranker.computeModelScoreOnTraining(rlStart, rlEnd, martStart);
			else if(type == 3)
				score = ranker.computeModelScoreOnValidation(rlStart, rlEnd);
		}
	}
}
