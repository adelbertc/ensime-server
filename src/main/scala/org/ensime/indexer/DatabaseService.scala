package org.ensime.indexer

import akka.event.slf4j.SLF4JLogging
import java.io.File
import java.sql.Timestamp
import org.apache.commons.vfs2.FileObject
import com.jolbox.bonecp.BoneCPDataSource

import org.scalaquery.session._
import org.scalaquery.session.Database.threadLocalSession
import org.scalaquery.ql._
import org.scalaquery.ql.TypeMapper._
import org.scalaquery.ql.extended.H2Driver.Implicit._
import org.scalaquery.ql.extended.{ ExtendedTable => Table }

class DatabaseService(dir: File) extends SLF4JLogging {
  lazy val db = {
    // MVCC plus connection pooling speeds up the tests ~10%
    val url = "jdbc:h2:file:" + dir.getAbsolutePath + "/db;MVCC=TRUE"
    val driver = "org.h2.Driver"
    //Database.forURL(url, driver = driver)

    // http://jolbox.com/benchmarks.html
    val ds = new BoneCPDataSource()
    ds.setDriverClass(driver)
    ds.setJdbcUrl(url)
    Database.forDataSource(ds)
  }

  import DatabaseService._

  if (!dir.exists) {
    log.info("creating the search database")
    dir.mkdirs()
    db withSession {
      (FileChecks.ddl ++ FqnSymbols.ddl).create
    }
  }

  // TODO hierarchy
  // TODO reverse lookup table

  // file with last modified time
  def knownFiles(): List[FileCheck] = {
    db.withSession {
      (for (row <- FileChecks) yield row).list
    }
  }.map {
    FileCheck.apply
  }

  def removeFiles(files: List[FileObject]): Int = db.withSession {
    val restrict = files.map(_.getName.getURI)
    val q1 = for {
      row <- FqnSymbols
      if row.file inSet restrict
    } yield row
    q1.delete

    val q2 = for {
      row <- FileChecks
      if row.filename inSet restrict
    } yield row
    q2.delete
  }

  def outOfDate(f: FileObject): Boolean = db withSession {
    val uri = f.getName.getURI
    val modified = f.getContent.getLastModifiedTime

    val query = for {
      filename <- Parameters[String]
      u <- FileChecks if u.filename === filename
    } yield u.timestamp

    query(uri).list.map(_.getTime).headOption match {
      case Some(timestamp) if timestamp < modified => true
      case Some(_) => false
      case _ => true
    }
  }

  def persist(check: FileCheck, symbols: Seq[FqnSymbol]): Unit =
    db.withSession {
      FileChecks.insert(check)
      val tuples = symbols.map { e => FqnSymbol.unapply(e).get }
      FqnSymbols.insertAll(tuples: _*)
    }

  def find(fqn: String): Option[FqnSymbol] = db.withSession {
    {
      for {
        row <- FqnSymbols
        if row.fqn === fqn
      } yield row
    }.list.headOption
  }.map(FqnSymbol.apply)

  import IndexService._
  def find(fqns: List[FqnIndex]): List[FqnSymbol] = {
    db.withSession {
      val restrict = fqns.map(_.fqn)
      val query = for {
        row <- FqnSymbols
        if row.fqn inSet restrict
      } yield row

      val results = query.list.map(FqnSymbol.apply).groupBy(_.fqn)
      restrict.flatMap(results.get(_).map(_.head))
    }
  }
}

object DatabaseService {
  // I absolutely **HATE** this DSL bullshit. I want to use the raw
  // SQL!! But it looks like slick/scala-2.11 don't play well at the
  // moment: https://issues.scala-lang.org/browse/SI-8261
  // another advantage of the raw SQL and mappers is that our
  // case classes don't need to be bastardised to match what the
  // DSL can understand.

  // case class Checked(file: File, checked: Date)
  // db withSession { implicit s =>
  //   sqlu"""CREATE TABLE CHECKED(
  //            id INTEGER NOT NULL PRIMARY KEY,
  //            file VARCHAR(255) NOT NULL UNIQUE,
  //            checked TIMESTAMP)""".execute(s)
  //}

  case class FileCheck(id: Option[Int], filename: String, timestamp: Timestamp) {
    def file = vfilename(filename)
    def lastModified = timestamp.getTime
    def changed = file.getContent.getLastModifiedTime != lastModified
  }
  object FileCheck {
    def apply(t: (Option[Int], String, Timestamp)): FileCheck =
      new FileCheck(t._1, t._2, t._3)

    def apply(f: FileObject): FileCheck = {
      val name = f.getName.getURI
      val ts = new Timestamp(f.getContent.getLastModifiedTime)
      FileCheck(None, name, ts)
    }
  }
  object FileChecks extends Table[(Option[Int], String, Timestamp)]("FILECHECKS") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def filename = column[String]("filename")
    def timestamp = column[Timestamp]("timestamp")
    def * = id.? ~ filename ~ timestamp
    def idx = index("idx_filename", filename, unique = true)

    def insert(e: FileCheck)(implicit s: Session): Int = insert(FileCheck.unapply(e).get)(s)
  }

  case class FqnSymbol(
      id: Option[Int],
      file: String, // the underlying file
      path: String, // the VFS handle (e.g. classes in jars)
      fqn: String,
      descriptor: Option[String], // for methods
      internal: Option[String], // for fields
      source: Option[String], // VFS
      line: Option[Int],
      offset: Option[Int] // to be deprecated
      // future features:
      //    type: ??? --- better than descriptor/internal
      ) {
    // this is just as a helper until we can use more sensible
    // domain objects with slick
    def sourceFileObject = source.map(vfilename)

    // legacy: note that we can't distinguish class/trait
    def declAs: Symbol =
      if (descriptor.isDefined) 'method
      else if (internal.isDefined) 'field
      else 'class
  }
  object FqnSymbol {
    def apply(t: ((Option[Int], String, String, String, Option[String], Option[String], Option[String], Option[Int], Option[Int]))): FqnSymbol =
      new FqnSymbol(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9)
  }

  object FqnSymbols extends Table[(Option[Int], String, String, String, Option[String], Option[String], Option[String], Option[Int], Option[Int])]("FQN_SYMBOLS") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def file = column[String]("file")
    def path = column[String]("path")
    def fqn = column[String]("fqn")
    def descriptor = column[Option[String]]("descriptor")
    def internal = column[Option[String]]("internal")
    def source = column[Option[String]]("source handle")
    def line = column[Option[Int]]("line in source")
    def offset = column[Option[Int]]("offset in source")
    def * = id.? ~ file ~ path ~ fqn ~ descriptor ~ internal ~ source ~ line ~ offset
    def fqnIdx = index("idx_fqn", fqn, unique = false) // fqns are unique by type and sig
    def uniq = index("idx_uniq", fqn ~ descriptor ~ internal, unique = true)

    def insert(e: FqnSymbol)(implicit s: Session): Int = insert(FqnSymbol.unapply(e).get)(s)

    // insertAll cannot be hacked this way
    // something to do with https://issues.scala-lang.org/browse/SI-4626
  }
}
