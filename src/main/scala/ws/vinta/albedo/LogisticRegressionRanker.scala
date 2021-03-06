package ws.vinta.albedo

import org.apache.spark.SparkConf
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature._
import org.apache.spark.ml.recommendation.ALSModel
import org.apache.spark.ml.{Pipeline, PipelineModel, PipelineStage}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import ws.vinta.albedo.closures.UDFs._
import ws.vinta.albedo.evaluators.RankingEvaluator
import ws.vinta.albedo.evaluators.RankingEvaluator._
import ws.vinta.albedo.recommenders._
import ws.vinta.albedo.transformers._
import ws.vinta.albedo.utils.DatasetUtils._
import ws.vinta.albedo.utils.ModelUtils._

import scala.collection.mutable

object LogisticRegressionRanker {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
    if (scala.util.Properties.envOrElse("RUN_WITH_INTELLIJ", "false") == "true") {
      conf.setMaster("local[*]")
      conf.set("spark.driver.memory", "12g")
      //conf.setMaster("local-cluster[1, 3, 12288]")
      //conf.setJars(List("target/albedo-1.0.0-SNAPSHOT-uber.jar"))
      //conf.setMaster("spark://localhost:7077")
      //conf.set("spark.driver.memory", "2g")
      //conf.set("spark.executor.cores", "3")
      //conf.set("spark.executor.memory", "12g")
      //conf.setJars(List("target/albedo-1.0.0-SNAPSHOT-uber.jar"))
    }

    implicit val spark: SparkSession = SparkSession
      .builder()
      .appName("LogisticRegressionRanker")
      .config(conf)
      .getOrCreate()

    import spark.implicits._

    val sc = spark.sparkContext
    sc.setCheckpointDir(s"${settings.checkpointDir}")

    // Load Data

    val userProfileDF = loadUserProfileDF().cache()

    val repoProfileDF = loadRepoProfileDF().cache()

    val rawStarringDS = loadRawStarringDS().cache()

    // Feature Engineering

    val booleanColumnNames = mutable.ArrayBuffer.empty[String]
    val continuousColumnNames = mutable.ArrayBuffer.empty[String]
    val categoricalColumnNames = mutable.ArrayBuffer.empty[String]
    val listColumnNames = mutable.ArrayBuffer.empty[String]
    val textColumnNames = mutable.ArrayBuffer.empty[String]

    // User Profile

    booleanColumnNames += "user_has_null"
    booleanColumnNames += "user_knows_web"
    booleanColumnNames += "user_knows_backend"
    booleanColumnNames += "user_knows_frontend"
    booleanColumnNames += "user_knows_mobile"
    booleanColumnNames += "user_knows_devops"
    booleanColumnNames += "user_knows_data"
    booleanColumnNames += "user_knows_recsys"
    booleanColumnNames += "user_is_lead"
    booleanColumnNames += "user_is_scholar"
    booleanColumnNames += "user_is_freelancer"
    booleanColumnNames += "user_is_junior"
    booleanColumnNames += "user_is_pm"
    booleanColumnNames += "user_has_blog"

    continuousColumnNames += "user_public_repos_count"
    continuousColumnNames += "user_public_gists_count"
    continuousColumnNames += "user_followers_count"
    continuousColumnNames += "user_following_count"
    continuousColumnNames += "user_followers_following_ratio"
    continuousColumnNames += "user_days_between_created_at_today"
    continuousColumnNames += "user_days_between_updated_at_today"
    continuousColumnNames += "user_starred_repos_count"
    continuousColumnNames += "user_avg_daily_starred_repos_count"

    categoricalColumnNames += "user_account_type"
    categoricalColumnNames += "user_binned_company"
    categoricalColumnNames += "user_binned_location"

    listColumnNames += "user_recent_repo_languages"
    listColumnNames += "user_recent_repo_topics"

    textColumnNames += "user_clean_bio"
    textColumnNames += "user_recent_repo_descriptions"

    // Repo Profile

    booleanColumnNames += "repo_has_issues"
    booleanColumnNames += "repo_has_projects"
    booleanColumnNames += "repo_has_downloads"
    booleanColumnNames += "repo_has_wiki"
    booleanColumnNames += "repo_has_pages"
    booleanColumnNames += "repo_has_null"
    booleanColumnNames += "repo_has_activities_in_60days"
    booleanColumnNames += "repo_has_homepage"
    booleanColumnNames += "repo_is_vinta_starred"

    continuousColumnNames += "repo_size"
    continuousColumnNames += "repo_stargazers_count"
    continuousColumnNames += "repo_forks_count"
    continuousColumnNames += "repo_subscribers_count"
    continuousColumnNames += "repo_open_issues_count"
    continuousColumnNames += "repo_days_between_created_at_today"
    continuousColumnNames += "repo_days_between_updated_at_today"
    continuousColumnNames += "repo_days_between_pushed_at_today"
    continuousColumnNames += "repo_subscribers_stargazers_ratio"
    continuousColumnNames += "repo_forks_stargazers_ratio"
    continuousColumnNames += "repo_open_issues_stargazers_ratio"

    categoricalColumnNames += "repo_owner_type"
    categoricalColumnNames += "repo_binned_language"

    listColumnNames += "repo_clean_topics"

    textColumnNames += "repo_text"

    // Prepare the Feature Pipeline

    val maxStarredReposCount = if (scala.util.Properties.envOrElse("RUN_WITH_INTELLIJ", "false") == "true") 30 else 4000

    println(s"maxStarredReposCount: $maxStarredReposCount")

    val reducedStarringDFpath = s"${settings.dataDir}/${settings.today}/reducedStarringDF-$maxStarredReposCount.parquet"
    val reducedStarringDF = loadOrCreateDataFrame(reducedStarringDFpath, () => {
      val userStarredReposCountDF = rawStarringDS
        .groupBy($"user_id")
        .agg(count("*").alias("user_starred_repos_count"))

      rawStarringDS
        .join(userStarredReposCountDF, Seq("user_id"))
        .where($"user_starred_repos_count" <= maxStarredReposCount)
        .select($"user_id", $"repo_id", $"starred_at", $"starring")
    })
    .repartition($"user_id")
    .cache()

    val profileStarringDF = reducedStarringDF
      .join(userProfileDF, Seq("user_id"))
      .join(repoProfileDF, Seq("repo_id"))
      .cache()

    categoricalColumnNames += "user_id"
    categoricalColumnNames += "repo_id"

    // Build the Feature Pipeline

    val userRepoTransformer = new UserRepoTransformer()
      .setInputCols(Array("repo_language", "user_recent_repo_languages"))

    continuousColumnNames += "repo_language_index_in_user_recent_repo_languages"
    continuousColumnNames += "repo_language_count_in_user_recent_repo_languages"

    val alsModelPath = s"${settings.dataDir}/${settings.today}/alsModel.parquet"
    val alsModel = ALSModel.load(alsModelPath)
      .setUserCol("user_id")
      .setItemCol("repo_id")
      .setPredictionCol("als_score")
      .setColdStartStrategy("drop")

    continuousColumnNames += "als_score"

    val categoricalTransformers = categoricalColumnNames.flatMap((columnName: String) => {
      val stringIndexer = new StringIndexer()
        .setInputCol(columnName)
        .setOutputCol(s"${columnName}__idx")
        .setHandleInvalid("keep")

      val oneHotEncoder = new OneHotEncoder()
        .setInputCol(s"${columnName}__idx")
        .setOutputCol(s"${columnName}__ohe")
        .setDropLast(false)

      Array(stringIndexer, oneHotEncoder)
    })

    val listTransformers = listColumnNames.flatMap((columnName: String) => {
      val countVectorizerModel = new CountVectorizer()
        .setInputCol(columnName)
        .setOutputCol(s"${columnName}__cv")
        .setMinDF(10)
        .setMinTF(1)

      Array(countVectorizerModel)
    })

    val textTransformers = textColumnNames.flatMap((columnName: String) => {
      val hanLPTokenizer = new HanLPTokenizer()
        .setInputCol(columnName)
        .setOutputCol(s"${columnName}__words")
        .setShouldRemoveStopWords(true)

      val stopWordsRemover = new StopWordsRemover()
        .setInputCol(s"${columnName}__words")
        .setOutputCol(s"${columnName}__filtered_words")
        .setStopWords(StopWordsRemover.loadDefaultStopWords("english"))
      val word2VecModelPath = s"${settings.dataDir}/${settings.today}/word2VecModel.parquet"
      val word2VecModel = Word2VecModel.load(word2VecModelPath)
        .setInputCol(s"${columnName}__filtered_words")
        .setOutputCol(s"${columnName}__w2v")

      Array(hanLPTokenizer, stopWordsRemover, word2VecModel)
    })

    val finalBooleanColumnNames = booleanColumnNames.toArray
    val finalContinuousColumnNames = continuousColumnNames.toArray
    val finalCategoricalColumnNames = categoricalColumnNames.map(columnName => s"${columnName}__ohe").toArray
    val finalListColumnNames = listColumnNames.map(columnName => s"${columnName}__cv").toArray
    val finalTextColumnNames = textColumnNames.map(columnName => s"${columnName}__w2v").toArray
    val vectorAssembler = new SimpleVectorAssembler()
      .setInputCols(finalBooleanColumnNames ++ finalContinuousColumnNames ++ finalCategoricalColumnNames ++ finalListColumnNames ++ finalTextColumnNames)
      .setOutputCol("features")

    val featureStages = mutable.ArrayBuffer.empty[PipelineStage]
    featureStages += userRepoTransformer
    featureStages += alsModel
    featureStages ++= categoricalTransformers
    featureStages ++= listTransformers
    featureStages ++= textTransformers
    featureStages += vectorAssembler

    val featurePipeline = new Pipeline().setStages(featureStages.toArray)

    val featurePipelinePath = s"${settings.dataDir}/${settings.today}/rankerFeaturePipeline-$maxStarredReposCount.parquet"
    val featurePipelineModel = loadOrCreateModel[PipelineModel](PipelineModel, featurePipelinePath, () => {
      featurePipeline.fit(profileStarringDF)
    })

    // Handle Imbalanced Data

    val negativePositiveRatio = 1.0

    println(s"negativePositiveRatio: $negativePositiveRatio")

    val balancedStarringDFpath = s"${settings.dataDir}/${settings.today}/balancedStarringDF-$maxStarredReposCount-$negativePositiveRatio.parquet"
    val balancedStarringDF = loadOrCreateDataFrame(balancedStarringDFpath, () => {
      val popularReposDS = loadPopularRepoDF()
      val popularRepos = popularReposDS
        .select($"repo_id".as[Int])
        .collect()
        .to[mutable.LinkedHashSet]
      val bcPopularRepos = sc.broadcast(popularRepos)

      val negativeBalancer = new NegativeBalancer(bcPopularRepos)
        .setUserCol("user_id")
        .setItemCol("repo_id")
        .setTimeCol("starred_at")
        .setLabelCol("starring")
        .setNegativeValue(0.0)
        .setNegativePositiveRatio(negativePositiveRatio)
      negativeBalancer.transform(reducedStarringDF)
    })
    .repartition($"user_id")
    .cache()

    // Prepare the Model Pipeline

    val profileBalancedStarringDF = balancedStarringDF
      .join(userProfileDF, Seq("user_id"))
      .join(repoProfileDF, Seq("repo_id"))
      .cache()

    balancedStarringDF.unpersist()

    val featuredBalancedStarringDFpath = s"${settings.dataDir}/${settings.today}/rankerFeaturedBalancedStarringDF-$maxStarredReposCount-$negativePositiveRatio.parquet"
    val featuredBalancedStarringDF = loadOrCreateDataFrame(featuredBalancedStarringDFpath, () => {
      val df = featurePipelineModel.transform(profileBalancedStarringDF)
      val keepColumnName = df.columns.filter((columnName: String) => {
        !columnName.endsWith("__idx") &&
        !columnName.endsWith("__ohe") &&
        !columnName.endsWith("__cv") &&
        !columnName.endsWith("__words") &&
        !columnName.endsWith("__filtered_words") &&
        !columnName.endsWith("__w2v")
      })
      df.select(keepColumnName.map(col): _*)
    })
    .cache()

    println(s"featuredBalancedStarringDF.columns: " + featuredBalancedStarringDF.columns.mkString(", "))

    // Split Data

    val weights = if (scala.util.Properties.envOrElse("RUN_WITH_INTELLIJ", "false") == "true") Array(0.7, 0.3) else Array(0.95, 0.05)
    val Array(trainingDF, testDF) = featuredBalancedStarringDF.randomSplit(weights)

    val trainingFeaturedDF = trainingDF
      .repartition($"user_id")
      .cache()

    val testFeaturedDF = testDF
      .repartition($"user_id")
      .cache()

    val largeUserIds = testFeaturedDF.select($"user_id").distinct().map(row => row.getInt(0)).collect().toList
    val sampledUserIds = scala.util.Random.shuffle(largeUserIds).take(200) :+ 652070
    val testUserDF = spark.createDataFrame(sampledUserIds.map(Tuple1(_)))
      .toDF("user_id")
      .cache()

    // Build the Model Pipeline

    val weightSQL = """
    SELECT *,
           1.0 AS default_weight,
           IF (starring = 1.0, 0.9, 0.1) AS positive_weight,
           IF (starring = 1.0 AND datediff(current_date(), starred_at) <= 365, 0.9, 0.1) AS positive_starred_weight,
           IF (starring = 1.0 AND datediff(current_date(), repo_created_at) <= 730, 0.9, 0.1) AS positive_created_weight,
           IF (starring = 1.0, ROUND(CAST(repo_created_at AS INT) / (60 * 60 * 24 * 7), 0), 1.0) AS positive_created_week_weight
    FROM __THIS__
    """.stripMargin
    val weightTransformer = new SQLTransformer()
      .setStatement(weightSQL)

    println(s"weightSQL: $weightSQL")

    val lr = new LogisticRegression()
      .setMaxIter(300)
      .setRegParam(0.7)
      .setElasticNetParam(0.0)
      .setStandardization(true)
      .setLabelCol("starring")
      .setFeaturesCol("features")
      .setWeightCol("positive_starred_weight")

    println(lr.explainParams())

    val modelStages = mutable.ArrayBuffer.empty[PipelineStage]
    modelStages += weightTransformer
    modelStages += lr

    val modelPipeline = new Pipeline().setStages(modelStages.toArray)

    val modelPipelinePath = s"${settings.dataDir}/${settings.today}/rankerModelPipeline-$maxStarredReposCount-$negativePositiveRatio-${lr.getMaxIter}-${lr.getRegParam}-${lr.getElasticNetParam}-${lr.getWeightCol}.parquet"
    val modelPipelineModel = loadOrCreateModel[PipelineModel](PipelineModel, modelPipelinePath, () => {
      modelPipeline.fit(trainingFeaturedDF)
    })

    // Evaluate the Model: Classification

    val testRankedDF = modelPipelineModel.transform(testFeaturedDF)

    val binaryClassificationEvaluator = new BinaryClassificationEvaluator()
      .setMetricName("areaUnderROC")
      .setRawPredictionCol("rawPrediction")
      .setLabelCol("starring")

    val classificationMetric = binaryClassificationEvaluator.evaluate(testRankedDF)

    println(s"${binaryClassificationEvaluator.getMetricName} = $classificationMetric")
    // areaUnderROC = 0.9425269994859455

    // Generate Candidates

    val topK = 30

    val alsRecommender = new ALSRecommender()
      .setUserCol("user_id")
      .setItemCol("repo_id")
      .setTopK(topK * 2)

    val contentRecommender = new ContentRecommender()
      .setUserCol("user_id")
      .setItemCol("repo_id")
      .setTopK(topK)
      .setEnableEvaluationMode(true)

    val curationRecommender = new CurationRecommender()
      .setUserCol("user_id")
      .setItemCol("repo_id")
      .setTopK(topK)

    val popularityRecommender = new PopularityRecommender()
      .setUserCol("user_id")
      .setItemCol("repo_id")
      .setTopK(topK)

    val recommenders = mutable.ArrayBuffer.empty[Recommender]
    recommenders += alsRecommender
    //recommenders += contentRecommender
    recommenders += curationRecommender
    recommenders += popularityRecommender

    val candidateDF = recommenders
      .map((recommender: Recommender) => recommender.recommendForUsers(testUserDF))
      .reduce(_ union _)
      .select($"user_id", $"repo_id")
      .distinct()
      .select($"user_id", $"repo_id", current_timestamp().alias("starred_at"), lit(1.0).alias("starring"))
      .repartition($"user_id")
      .cache()

    // Predict the Ranking

    val profileCandidateDF = candidateDF
      .join(userProfileDF, Seq("user_id"))
      .join(repoProfileDF, Seq("repo_id"))
      .cache()

    val featuredCandidateDF = featurePipelineModel
      .transform(profileCandidateDF)
      .cache()

    val rankedCandidateDF = modelPipelineModel
      .transform(featuredCandidateDF)
      .cache()

    rankedCandidateDF
      .where($"user_id" === 652070)
      .select("user_id", "repo_id", "prediction", "probability")
      .orderBy(toArrayUDF($"probability").getItem(1).desc)
      .limit(topK)
      .show(false)

    // Evaluate the Model: Ranking

    val userActualItemsDF = loadUserActualItemsDF(topK).cache()

    val userPredictedItemsDF = rankedCandidateDF
      .transform(intoUserPredictedItems($"user_id", $"repo_id", toArrayUDF($"probability").getItem(1).desc, topK))
      .cache()

    val rankingEvaluator = new RankingEvaluator(userActualItemsDF)
      .setMetricName("NDCG@k")
      .setK(topK)
      .setUserCol("user_id")
      .setItemsCol("items")
    val rankingMetric = rankingEvaluator.evaluate(userPredictedItemsDF)

    println(s"${rankingEvaluator.getFormattedMetricName} = $rankingMetric")
    // NDCG@30 = 0.021114356461615493

    spark.stop()
  }
}