package com.github.mjakubowski84.parquet4s

import com.github.mjakubowski84.parquet4s.etl.CompoundParquetIterable
import com.github.mjakubowski84.parquet4s.stats.FileStats
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.filter2.compat.FilterCompat
import org.apache.parquet.hadoop.ParquetReader as HadoopParquetReader
import org.apache.parquet.hadoop.api.ReadSupport
import org.apache.parquet.io.InputFile
import org.apache.parquet.schema.{MessageType, Type}
import org.slf4j.{Logger, LoggerFactory}

import java.util.TimeZone

object ParquetReader extends IOOps {

  /** Builds an instance of [[ParquetIterable]]
    * @tparam T
    *   type of data generated by the source.
    */
  trait Builder[T] {

    /** @param options
      *   configuration of how Parquet files should be read
      */
    def options(options: ParquetReader.Options): Builder[T]

    /** @param filter
      *   optional before-read filter; no filtering is applied by default; check [[Filter]] for more details
      */
    def filter(filter: Filter): Builder[T]

    /** Attempt to read data as partitioned. Partition names must follow Hive format. Partition values will be set in
      * read records to corresponding fields.
      */
    def partitioned: Builder[T]

    /** @param path
      *   [[Path]] to Parquet files, e.g.: {{{Path("file:///data/users")}}}
      * @param decoder
      *   decodes [[RowParquetRecord]] to your data type
      * @return
      *   final [[ParquetIterable]]
      * @throws scala.IllegalArgumentException
      *   when reading inconsistent partition directory
      */
    def read(path: Path)(implicit decoder: ParquetRecordDecoder[T]): ParquetIterable[T]

    /** @param file
      *   the InputFile to read from
      * @param decoder
      *   decodes [[RowParquetRecord]] to your data type
      * @return
      *   final [[ParquetIterable]]
      */
    @experimental
    def read(file: InputFile)(implicit decoder: ParquetRecordDecoder[T]): ParquetIterable[T]
  }

  private case class BuilderImpl[T](
      options: ParquetReader.Options                               = ParquetReader.Options(),
      filter: Filter                                               = Filter.noopFilter,
      projectedSchemaResolverOpt: Option[ParquetSchemaResolver[T]] = None,
      columnProjections: Seq[ColumnProjection]                     = Seq.empty,
      readPartitions: Boolean                                      = false
  ) extends Builder[T] {
    override def options(options: ParquetReader.Options): Builder[T] =
      this.copy(options = options)

    override def filter(filter: Filter): Builder[T] =
      this.copy(filter = filter)

    override def partitioned: Builder[T] = this.copy(readPartitions = true)

    override def read(path: Path)(implicit decoder: ParquetRecordDecoder[T]): ParquetIterable[T] = {
      val valueCodecConfiguration = ValueCodecConfiguration(options)
      val hadoopConf              = options.hadoopConf

      if (readPartitions)
        partitionedIterable(path, valueCodecConfiguration, hadoopConf)
      else
        singleIterable(
          path                    = path,
          valueCodecConfiguration = valueCodecConfiguration,
          projectedSchemaOpt =
            projectedSchemaResolverOpt.map(implicit resolver => ParquetSchemaResolver.resolveSchema[T]),
          filterCompat = filter.toFilterCompat(valueCodecConfiguration),
          hadoopConf   = hadoopConf
        )
    }

    override def read(file: InputFile)(implicit decoder: ParquetRecordDecoder[T]): ParquetIterable[T] = {
      val valueCodecConfiguration = ValueCodecConfiguration(options)
      val hadoopConf              = options.hadoopConf

      singleIterable(
        file                    = file,
        valueCodecConfiguration = valueCodecConfiguration,
        projectedSchemaOpt =
          projectedSchemaResolverOpt.map(implicit resolver => ParquetSchemaResolver.resolveSchema[T]),
        filterCompat = filter.toFilterCompat(valueCodecConfiguration),
        hadoopConf   = hadoopConf
      )
    }

    private def partitionedIterable(
        path: Path,
        valueCodecConfiguration: ValueCodecConfiguration,
        hadoopConf: Configuration
    )(implicit decoder: ParquetRecordDecoder[T]): ParquetIterable[T] =
      findPartitionedPaths(path, hadoopConf) match {
        case Left(exception) =>
          throw exception
        case Right(partitionedDirectory) =>
          val projectedSchemaOpt = projectedSchemaResolverOpt.map(implicit resolver =>
            ParquetSchemaResolver.resolveSchema(partitionedDirectory.schema)
          )
          val iterables = PartitionFilter
            .filter(filter, valueCodecConfiguration, partitionedDirectory)
            .toSeq
            .map { case (filter, partitionedPath) =>
              singleIterable(
                path                    = partitionedPath.path,
                valueCodecConfiguration = valueCodecConfiguration,
                projectedSchemaOpt      = projectedSchemaOpt,
                filterCompat            = filter,
                hadoopConf              = hadoopConf
              ).appendTransformation(setPartitionValues(partitionedPath))
            }
          new CompoundParquetIterable[T](iterables)
      }

    private def singleIterable(
        path: Path,
        valueCodecConfiguration: ValueCodecConfiguration,
        projectedSchemaOpt: Option[MessageType],
        filterCompat: FilterCompat.Filter,
        hadoopConf: Configuration
    )(implicit decoder: ParquetRecordDecoder[T]): ParquetIterable[T] = {
      if (logger.isDebugEnabled) {
        logger.debug(s"Creating ParquetIterable for path $path")
      }
      ParquetIterable[T](
        iteratorFactory = () =>
          new ParquetIterator(
            HadoopParquetReader
              .builder[RowParquetRecord](new ParquetReadSupport(projectedSchemaOpt, columnProjections), path.toHadoop)
              .withConf(hadoopConf)
              .withFilter(filterCompat)
          ),
        valueCodecConfiguration = valueCodecConfiguration,
        stats                   = Stats(path, valueCodecConfiguration, hadoopConf, projectedSchemaOpt, filterCompat)
      )
    }

    private def singleIterable(
        file: InputFile,
        valueCodecConfiguration: ValueCodecConfiguration,
        projectedSchemaOpt: Option[MessageType],
        filterCompat: FilterCompat.Filter,
        hadoopConf: Configuration
    )(implicit decoder: ParquetRecordDecoder[T]): ParquetIterable[T] = {
      if (logger.isDebugEnabled) {
        logger.debug(s"Creating ParquetIterable for file $file")
      }
      ParquetIterable[T](
        iteratorFactory = () =>
          new ParquetIterator(
            new HadoopParquetReader.Builder[RowParquetRecord](file) {
              override def getReadSupport: ReadSupport[RowParquetRecord] = new ParquetReadSupport(projectedSchemaOpt)
            }
              .withConf(hadoopConf)
              .withFilter(filterCompat)
          ),
        valueCodecConfiguration = valueCodecConfiguration,
        stats                   = new FileStats(file, valueCodecConfiguration, projectedSchemaOpt)
      )
    }

    private def setPartitionValues(partitionedPath: PartitionedPath)(
        record: RowParquetRecord
    ): Iterable[RowParquetRecord] =
      Option(
        partitionedPath.partitions.foldLeft(record) { case (currentRecord, (columnPath, value)) =>
          currentRecord.updated(columnPath, BinaryValue(value))
        }
      )
  }

  /** Configuration settings that are used during decoding or reading Parquet files
    *
    * @param timeZone
    *   set it to [[java.util.TimeZone]] which was used to encode time-based data that you want to read; machine's time
    *   zone is used by default
    * @param hadoopConf
    *   use it to programmatically override Hadoop's [[org.apache.hadoop.conf.Configuration]]
    */
  case class Options(
      timeZone: TimeZone        = TimeZone.getDefault,
      hadoopConf: Configuration = new Configuration()
  )

  override val logger: Logger = LoggerFactory.getLogger(this.getClass)

  /** Creates new [[ParquetIterable]] over data from given path. <br/> Path can represent local file or directory, HDFS,
    * AWS S3, Google Storage, Azure, etc. Please refer to Hadoop client documentation or your data provider in order to
    * know how to configure the connection.
    *
    * @note
    *   Remember to call `close()` on iterable in order to free resources!
    *
    * @param path
    *   [[Path]] to Parquet files, e.g.: {{{Path("file:///data/users")}}}
    * @param options
    *   configuration of how Parquet files should be read
    * @param filter
    *   optional before-read filtering; no filtering is applied by default; check [[Filter]] for more details
    * @tparam T
    *   type of data that represents the schema of the Parquet file, e.g.:
    *   {{{case class MyData(id: Long, name: String, created: java.sql.Timestamp)}}}
    */
  @deprecated("2.0.0", "use builder API by calling 'as[T]', 'projectedAs[T]', 'generic' or 'projectedGeneric'")
  def read[T: ParquetRecordDecoder: ParquetSchemaResolver](
      path: Path,
      options: Options = Options(),
      filter: Filter   = Filter.noopFilter
  ): ParquetIterable[T] =
    projectedAs[T].options(options).filter(filter).read(path)

  /** Creates [[Builder]] of Parquet reader for documents of type <i>T</i>.
    */
  def as[T]: Builder[T] = BuilderImpl()

  /** Creates [[Builder]] of Parquet reader for <i>projected</i> documents of type <i>T</i>. Due to projection reader
    * does not attempt to read all existing columns of the file but applies enforced projection schema.
    */
  def projectedAs[T: ParquetSchemaResolver]: Builder[T] = BuilderImpl(
    projectedSchemaResolverOpt = Option(implicitly[ParquetSchemaResolver[T]])
  )

  /** Creates [[Builder]] of Parquet reader returning generic records.
    */
  def generic: Builder[RowParquetRecord] = BuilderImpl()

  /** Creates [[Builder]] of Parquet reader returning <i>projected</i> generic records. Due to projection reader does
    * not attempt to read all existing columns of the file but applies enforced projection schema.
    */
  def projectedGeneric(projectedSchema: MessageType): Builder[RowParquetRecord] = BuilderImpl(
    projectedSchemaResolverOpt = Option(RowParquetRecord.genericParquetSchemaResolver(projectedSchema))
  )

  // format: off
  /** Creates [[Builder]] of Parquet reader returning <i>projected</i> generic records. Due to projection, reader does
    * not attempt to read all existing columns of the file but applies enforced projection schema. Besides simple
    * projection one can use aliases and extract values from nested fields - in a way similar to SQL.
    * <br/> <br/>
    * @example
    *   <pre> 
    *projectedGeneric(
    *  Col("foo").as[Int], // selects Int column "foo"
    *  Col("bar.baz".as[String]), // selects String field "bar.baz", creates column "baz" wih a value of "baz"
    *  Col("bar.baz".as[String].alias("bar_baz")) // selects String field "bar.baz", creates column "bar_baz" wih a value of "baz"
    *)
    *   </pre>  
    * @param col
    *   first column projection
    * @param cols
    *   next column projections
    */
  // format: on  
  def projectedGeneric(col: TypedColumnPath[?], cols: TypedColumnPath[?]*): Builder[RowParquetRecord] = {
    val (fields, columnProjections) =
      (col +: cols.toVector).zipWithIndex
        .foldLeft((Vector.empty[Type], Vector.empty[ColumnProjection])) {
          case ((fields, projections), (columnPath, ordinal)) =>
            val updatedFields      = fields :+ columnPath.toType
            val updatedProjections = projections :+ ColumnProjection(columnPath, ordinal)
            updatedFields -> updatedProjections
        }
    BuilderImpl(
      projectedSchemaResolverOpt = Option(RowParquetRecord.genericParquetSchemaResolver(Message.merge(fields))),
      columnProjections          = columnProjections
    )
  }

}
