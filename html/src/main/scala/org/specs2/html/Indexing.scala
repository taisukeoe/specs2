package org.specs2
package html

import foldm._, FoldM._, stream._, FoldProcessM._
import io.{DirectoryPath, FilePath, FileSystem}
import specification.core._
import scalaz.concurrent.Task
import scalaz.{Monoid, Reducer}
import scalaz.syntax.applicative._

/**
 * Fold functions to create index files
 */
object Indexing {

  /**
   * An Index fold creates an Index page based on all pages to index and
   * saves it to a given file path
   */
  def indexFold(path: FilePath) =
    fromReducer(Index.reducer).into[Task].mapFlatten((index: Index) => 
      Task.now(index) <* 
      FileSystem.writeFileTask(path, Index.toJson(index)))

  def createIndexedPages(env: Env, specifications: List[SpecStructure], outDir: DirectoryPath): Seq[IndexedPage] =
    specifications.map(createIndexedPage(env, outDir))

  def createIndexedPage(env: Env, outDir: DirectoryPath) = (spec: SpecStructure) => {
    IndexedPage(
      path     = SpecHtmlPage.outputPath(outDir, spec).relativeTo(outDir),
      title    = spec.header.showWords,
      contents = spec.texts.foldLeft(new StringBuilder)((res, cur) => res.append(cur.description.show)).toString,
      tags     = spec.fragments.fragments.collect { case Fragment(Marker(t,_,_), _, _) => t.names }.flatten.map(sanitize))
  }

  def createEntries(page: IndexedPage): Vector[IndexEntry] = 
    Vector(IndexEntry(page.title, page.contents, page.tags, page.path))

  /** remove quotes from names in order to add as json values */
  def sanitize(name: String): String =
    name.replace("\"", "\\\"")
}

case class IndexedPage(path: FilePath, title: String, contents: String, tags: IndexedSeq[String])

case class Index(entries: Vector[IndexEntry]) {
  def add(entry: IndexEntry) = copy(entries :+ entry)
  def add(other: Seq[IndexEntry]) = copy(entries ++ other)
}

object Index {

  val empty = Index(Vector())

  def toJson(index: Index): String = {
    s"""
       |var tipuesearch = {"pages": ${pages(index).mkString("[", ",\n", "]")}};
     """.stripMargin
  }

  def pages(index: Index): Seq[String] =
    index.entries.map(page)

  def page(entry: IndexEntry): String =
    s"""{"title":"${entry.title}", "text":"${sanitizeEntry(entry)}", "tags":${entry.tags.mkString("\""," ", "\"")}, "loc":"${entry.path.path}"}"""

  /**
   * the text that is used for indexing must be sanitized:
   * - no newlines
   * - ^ (because tipue search doesn't like it)
   * - no markdown characters
   */
  def sanitizeEntry(entry: IndexEntry): String =
    entry.text.replace("\"", "\\\"")
      .replace("\n", "")
      .replace("^", "")
      .replace("#####", "")
      .replace("####", "")
      .replace("###", "")
      .replace("##", "")
      .replace("  * ", "")
      .replace("```", "")
      .replace("<s2>", "")
      .replace("</s2>", "")

  implicit def IndexMonoid: Monoid[Index] = new Monoid[Index] {
    def zero = Index(Vector())
    def append(a: Index, b: =>Index) = Index(a.entries ++ b.entries)
  }

  val reducer = Reducer.unitReducer((page: IndexedPage) => Index(Indexing.createEntries(page)))
}

case class IndexEntry(title: String, text: String, tags: IndexedSeq[String], path: FilePath)

