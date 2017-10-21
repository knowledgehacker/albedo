package ws.vinta.albedo

import org.apache.spark.SparkConf
import org.apache.spark.ml.feature._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import ws.vinta.albedo.transformers.{CoreNLPLemmatizer, HanLPTokenizer}
import ws.vinta.albedo.utils.DatasetUtils._
import ws.vinta.albedo.utils.ModelUtils._

object Word2VecBuilder {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
    conf.set("spark.driver.memory", "4g")
    conf.set("spark.executor.memory", "12g")
    conf.set("spark.executor.cores", "4")

    implicit val spark: SparkSession = SparkSession
      .builder()
      .appName("Word2VecBuilder")
      .config(conf)
      .getOrCreate()

    import spark.implicits._

    // Load Data

    val rawUserInfoDS = loadRawUserInfoDS()

    val rawRepoInfoDS = loadRawRepoInfoDS()

    // Train the Model

    val userTextDF = rawUserInfoDS
      .withColumn("text", concat_ws(", ", $"login", $"name", $"company", $"location", $"bio"))
      .select($"user_id", $"login", $"text")

    val repoTextDF = rawRepoInfoDS
      .withColumn("text", concat_ws(", ", $"owner_username", $"name", $"language", $"description", $"topics"))
      .select($"repo_id", $"full_name", $"text")

    val corpusDF = userTextDF.select($"text").union(repoTextDF.select($"text"))
    corpusDF.cache()

    val columnName = "text"

    val hanLPTokenizer = new HanLPTokenizer()
      .setInputCol(columnName)
      .setOutputCol(s"${columnName}_words")
      .setShouldRemoveStopWords(true)
    val tokenizedDF = hanLPTokenizer.transform(corpusDF)

    val stopWordsRemover = new StopWordsRemover()
      .setInputCol(s"${columnName}_words")
      .setOutputCol(s"${columnName}_filtered_words")
      .setStopWords(StopWordsRemover.loadDefaultStopWords("english"))
    val filteredDF = stopWordsRemover.transform(tokenizedDF)

    val coreNLPLemmatizer = new CoreNLPLemmatizer()
      .setInputCol(s"${columnName}_filtered_words")
      .setOutputCol(s"${columnName}_lemmatized_words")
    val lemmatizedDF = coreNLPLemmatizer.transform(filteredDF)

    val finalDF = lemmatizedDF

    val word2VecModelPath = s"${settings.dataDir}/${settings.today}/word2VecModel.parquet"
    val word2VecModel = loadOrCreateModel[Word2VecModel](Word2VecModel, word2VecModelPath, () => {
      val word2Vec = new Word2Vec()
        .setInputCol(s"${columnName}_lemmatized_words")
        .setOutputCol(s"${columnName}_w2v")
        .setMaxIter(30)
        .setVectorSize(200)
        .setWindowSize(5)
        .setMinCount(10)
      word2Vec.fit(finalDF)
    })

    val word2vecDF = word2VecModel.transform(finalDF)
    word2vecDF.show(false)

    spark.stop()
  }
}