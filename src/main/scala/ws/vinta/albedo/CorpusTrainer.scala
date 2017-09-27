package ws.vinta.albedo

import com.databricks.spark.avro._
import org.apache.spark.ml.feature.{RegexTokenizer, StopWordsRemover, Word2Vec}
import org.apache.spark.sql.SparkSession

object CorpusTrainer {
  def main(args: Array[String]): Unit = {
    implicit val spark: SparkSession = SparkSession
      .builder()
      .appName("GitHubCorpusTrainer")
      .getOrCreate()

    val sc = spark.sparkContext

    val repoDescriptionDF = spark.read.avro(s"${settings.dataDir}/ghtorrent/repo_info_reduced.avro")
    repoDescriptionDF.cache()
    println(repoDescriptionDF.count())

    // TODO: 處理中文分詞
    val regexTokenizer = new RegexTokenizer()
      .setToLowercase(true)
      .setInputCol("description")
      .setOutputCol("words")
      .setPattern("[\\w-_]+").setGaps(false)
    val tokenizedDF = regexTokenizer.transform(repoDescriptionDF)

    val stopWordsRemover = new StopWordsRemover()
      .setInputCol("words")
      .setOutputCol("filtered_words")
    val filteredDF = stopWordsRemover.transform(tokenizedDF)

    val word2Vec = new Word2Vec()
      .setInputCol("filtered_words")
      .setOutputCol("word2vec")
      .setVectorSize(200)
      .setWindowSize(5)
      .setMinCount(5)
    val word2VecModel = word2Vec.fit(filteredDF)

    word2VecModel.save(s"${settings.dataDir}/${settings.today}/word2VecModel.parquet")

    spark.stop()
  }
}