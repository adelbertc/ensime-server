package org.ensime.indexer

import DatabaseService._
import akka.event.slf4j.SLF4JLogging
import java.sql.SQLException
import java.util.concurrent.Executors
import com.google.common.io.ByteStreams
import org.apache.commons.vfs2._
import org.ensime.config.EnsimeConfig
import pimpathon.file._
import scala.concurrent.backport.Future
import scala.util.Properties
import scala.concurrent.backport.ExecutionContext.Implicits.global

/**
 * Provides methods to perform ENSIME-specific indexing tasks,
 * receives events that require an index update, and provides
 * searches against the index.
 *
 * We have an H2 database for storing relational information
 * and Lucene for advanced indexing.
 */
class SearchService(
  config: EnsimeConfig,
  resolver: SourceResolver) extends ClassfileIndexer
    with ClassfileListener
    with SLF4JLogging {

  private val version = "1.0"

  private val index = new IndexService(config.cacheDir / ("index-" + version))
  private val db = new DatabaseService(config.cacheDir / ("sql-" + version))

  // don't use Global because it stalls trivial tasks
  private object worker {
    implicit val context = concurrent.backport.ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors)
    )
  }

  /**
   * Indexes everything, making best endeavours to avoid scanning what
   * is unnecessary (e.g. we already know that a jar or classfile has
   * been indexed).
   *
   * The decision of what will be indexed is performed syncronously,
   * as is the removal of stale data, but the itself itself is
   * performed asyncronously.
   *
   * @return the number of files estimated to be (removed, indexed)
   *         from the index and database. This is only an estimate
   *         because we may not have TODO
   */
  def refresh(): Future[(Int, Int)] = {
    def scan(f: FileObject) = f.findFiles(ClassfileSelector) match {
      case null => Nil
      case res => res.toList
    }

    // TODO visibility test/main and which module is viewed (a Lucene concern, not H2)

    val jarUris = config.allJars.map(vfile).map(_.getName.getURI)

    // remove stale entries: must be before index or INSERT/DELETE races
    val stale = for {
      known <- db.knownFiles()
      f = known.file
      name = f.getName.getURI
      if !f.exists || known.changed ||
        (name.endsWith(".jar") && !jarUris(name))
    } yield f

    log.info("removing " + stale.size + " stale files from the index")
    if (log.isTraceEnabled)
      log.trace("STALE = " + stale)

    // individual DELETEs in H2 are really slow
    val removing = stale.grouped(100).toSeq.map { files =>
      Future {
        index.remove(files)
        db.removeFiles(files)
      }(worker.context)
    }

    val removed = Future.sequence(removing).map(_ => stale.size)

    val bases = {
      config.modules.flatMap {
        case (name, m) =>
          scan(m.target) ::: scan(m.testTarget) :::
            m.compileJars.map(vfile) ::: m.testJars.map(vfile)
      }
    }.toSet ++ config.javaLib.map(vfile).toIterable

    // start indexing after all deletes have completed (not pretty)
    val indexing = removed.map { _ =>
      // could potentially do the db lookup in parallel
      bases.filter(db.outOfDate).toList map {
        case classfile if classfile.getName.getExtension == "class" => Future[Unit] {
          val check = FileCheck(classfile)
          val symbols = extractSymbols(classfile, classfile)
          persist(check, symbols)
        }(worker.context)

        case jar => Future[Unit] {
          log.debug("indexing " + jar)
          val check = FileCheck(jar)
          val symbols = scan(vjar(jar)) flatMap (extractSymbols(jar, _))
          persist(check, symbols)
        }(worker.context)
      }
    }

    val indexed = indexing.flatMap { w => Future.sequence(w) }.map(_.size)
    indexed onComplete { _ =>
      // delayed commits speedup initial indexing time
      log.debug("committing index to disk...")
      index.commit()
      log.debug("...done committing index")
    }

    for {
      r <- removed
      i <- indexed
    } yield (r, i)
  }

  private def persist(check: FileCheck, symbols: List[FqnSymbol]): Unit = try {
    index.persist(check, symbols)
    db.persist(check, symbols)
  } catch {
    case e: SQLException =>
      // likely a timing issue or corner-case dupe FQNs
      log.warn("failed to insert $symbols " + e.getClass + ": " + e.getMessage)
  }

  private val blacklist = Set("sun/", "sunw/", "com/sun/")
  private val ignore = Set("$$anonfun$", "$worker$")
  import org.ensime.util.RichFileObject._
  private def extractSymbols(container: FileObject, f: FileObject): List[FqnSymbol] = {
    f.pathWithinArchive match {
      case Some(relative) if blacklist.exists(relative.startsWith) => Nil
      case _ =>
        val name = container.getName.getURI
        val path = f.getName.getURI
        val (clazz, refs) = indexClassfile(f)

        // TODO: cross reference with the depickler

        val source = resolver.resolve(clazz.name.pack, clazz.source)
        val sourceUri = source.map(_.getName.getURI)

        // very expensive. we'd like to remove the need for offsets in the
        // swank protocol to avoid doing this. Doing it on the fly is far
        // too expensive for end users.
        val lineOffsets = source.map { fo =>
          val data = ByteStreams.toByteArray(fo.getContent.getInputStream)
          val nl = '\n'.toInt // should count only once even on windows
          var i, lines = 0
          var offsets: List[Int] = 0 :: 0 :: Nil
          while (i < data.length) {
            if (data(i) == nl) offsets ::= i
            i += 1
          }
          offsets.reverse
        }
        def offset(lineOpt: Option[Int]) = for {
          line <- lineOpt
          offsets <- lineOffsets
        } yield offsets.lift(line).getOrElse(0)

        // TODO: other types of visibility when we get more sophisticated
        if (clazz.access != Public) Nil
        else FqnSymbol(None, name, path, clazz.name.fqnString, None, None, sourceUri, clazz.source.line, offset(clazz.source.line)) ::
          clazz.methods.toList.filter(_.access == Public).map { method =>
            val descriptor = method.descriptor.descriptorString
            FqnSymbol(None, name, path, method.name.fqnString, Some(descriptor), None, sourceUri, method.line, offset(method.line))
          } ::: clazz.fields.toList.filter(_.access == Public).map { field =>
            val internal = field.clazz.internalString
            FqnSymbol(None, name, path, field.name.fqnString, None, Some(internal), sourceUri, clazz.source.line, offset(clazz.source.line))
          }
    }
  }.filterNot(sym => ignore.exists(sym.fqn.contains))

  // TODO: provide context (user's current module and main/test)
  /** free-form search for classes */
  def searchClasses(query: String, max: Int): List[FqnSymbol] = {
    val fqns = index.searchClasses(query, max)
    db.find(fqns) take max
  }

  /** free-form search for classes, fields and methods */
  def searchClassesFieldsMethods(query: String, max: Int): List[FqnSymbol] = {
    val fqns = index.searchClassesFieldsMethods(query, max)
    db.find(fqns) take max
  }

  /** only for exact fqns */
  def findUnique(fqn: String): Option[FqnSymbol] = db.find(fqn)

  def classfileAdded(f: FileObject): Unit = Future {
    val syms = extractSymbols(f, f)
    persist(FileCheck(f), syms)
    index.commit()
  }(worker.context)

  def classfileRemoved(f: FileObject): Unit = Future {
    index.remove(List(f))
    index.commit()
    db.removeFiles(List(f))
  }(worker.context)

  def classfileChanged(f: FileObject): Unit = Future {
    // hacky way of doing it, we could use UPDATE
    index.remove(List(f))
    db.removeFiles(List(f))
    val syms = extractSymbols(f, f)
    persist(FileCheck(f), syms)
    index.commit()
  }(worker.context)

}
