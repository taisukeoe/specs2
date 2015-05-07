package org.specs2
package reporter

import specification.core._
import specification.process.{Stats, Statistics}
import collection.Seqx
import foldm._, FoldM._, stream._, FoldProcessM._
import io._
import main.Arguments
import control._
import java.util.regex.Pattern._
import java.net.{JarURLConnection, URL}
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import HtmlBodyPrinter._
import Pandoc._
import ActionT._
import Actions._
import html._
import text.Trim._
import execute._
import text.NotNullStrings._
import Seqx._
import SearchPage._
import html.TableOfContents._
import SpecHtmlPage._

/**
 * Printer for html files
 */
trait HtmlPrinter extends Printer {

  def prepare(env: Env, specifications: List[SpecStructure]): Action[Unit]  = Actions.unit

  /** create an index for all the specifications, if required */
  def finalize(env: Env, specifications: List[SpecStructure]): Action[Unit] =
    getHtmlOptions(env.arguments) >>= { options: HtmlOptions =>
      createIndex(env, specifications, options).when(options.search) >>
      createToc(specifications, options.outDir, options.tocEntryMaxSize, env.fileSystem).when(options.toc) >>
      reportMissingSeeRefs(specifications, options.outDir).when(options.warnMissingSeeRefs)
    }

  /** @return a SinkTask for the Html output */
  def sink(env: Env, spec: SpecStructure): SinkTask[Fragment] = {
    ((Statistics.fold zip FoldId.list[Fragment]).into[Task] <*
     fromStart((getHtmlOptions(env.arguments) >>= (options => copyResources(env, options))).toTask.void)).mapFlatten { case (stats, fragments) =>
      val expecutedSpec = spec.copy(lazyFragments = () => Fragments(fragments:_*))
      getPandoc(env).flatMap {
          case None         => printHtml(env, expecutedSpec, stats)
          case Some(pandoc) => printHtmlWithPandoc(env, expecutedSpec, stats, pandoc)
      }.toTask
    }
  }

  /**
   * WITHOUT PANDOC
   */

  /**
   * Print the execution results as an Html file
   *
   *  - copy resources: css, javascript, template
   *  - create the file content using the template
   *  - output the file
   */
  def printHtml(env: Env, spec: SpecStructure, stats: Stats): Action[Unit] = {
    import env.fileSystem._
    for {
      options  <- getHtmlOptions(env.arguments)
      template <- readFile(options.template) ||| warnAndFail("No template file found at "+options.template.path, RunAborted)
      content  <- makeHtml(template, spec, stats, options, env.arguments)
      _        <- writeFile(outputPath(options.outDir, spec), content)
    } yield ()
  }

  /**
   * Get html options, possibly coming from the command line
   */
  def getHtmlOptions(arguments: Arguments): Action[HtmlOptions] = {
    import arguments.commandLine._
    val out = directoryOr("html.outdir", HtmlOptions.outDir)
    Actions.ok(HtmlOptions(
      outDir               = out,
      baseDir              = directoryOr("html.basedir",               HtmlOptions.baseDir),
      template             = fileOr(     "html.template",              HtmlOptions.template(out)),
      variables            = mapOr(      "html.variables",             HtmlOptions.variables),
      noStats              = boolOr(     "html.nostats",               HtmlOptions.noStats),
      search               = boolOr(     "html.search",                HtmlOptions.search),
      toc                  = boolOr(     "html.toc",                   HtmlOptions.toc),
      tocEntryMaxSize      = intOr(      "html.toc.entrymaxsize", HtmlOptions.tocEntryMaxSize),
      warnMissingSeeRefs   = boolOr(     "html.warn.missingseerefs",   HtmlOptions.warnMissingSeeRefs))
    )
  }


  /**
   * Create the html file content from:
   *
   *  - the template
   *  - the body of the file (built from the specification execution)
   */
  def makeHtml(template: String, spec: SpecStructure, stats: Stats, options: HtmlOptions, arguments: Arguments): Action[String] = {
    val body = makeBody(spec, stats, options, arguments, pandoc = true)
    val variables1 =
      options.templateVariables
        .updated("body",    body)
        .updated("title",   spec.wordsTitle)
        .updated("path",    outputPath(options.outDir, spec).path)

    HtmlTemplate.runTemplate(template, variables1)
  }

  /**
   * WITH PANDOC
   */

  /**
   * Print the execution results as an Html file
   *
   *  - copy resources: css, javascript, template
   *  - create the file content using the template and Pandoc (as an external process)
   */
  def printHtmlWithPandoc(env: Env, spec: SpecStructure, stats: Stats, pandoc: Pandoc): Action[Unit] = {
    import env.fileSystem._

    for {
      options  <- getHtmlOptions(env.arguments)
      _        <- withEphemeralFile(options.outDir | options.template.name) {
                    copyFile(options.outDir)(options.template) >>
                    makePandocHtml(spec, stats, pandoc, options, env)
                  }
    } yield ()
  }

  /**
   * Create the Html file by invoking Pandoc
   */
  def makePandocHtml(spec: SpecStructure, stats: Stats, pandoc: Pandoc, options: HtmlOptions, env: Env): Action[Unit] =  {
    import env.fileSystem._

    val variables1 =
      options.templateVariables
        .updated("title", spec.wordsTitle)

    val bodyFile: FilePath =
      options.outDir | FileName.unsafe("body-"+spec.hashCode)

    val outputFilePath = outputPath(options.outDir, spec)
    val pandocArguments = Pandoc.arguments(bodyFile, options.template, variables1, outputFilePath, pandoc)

    withEphemeralFile(bodyFile) {
      writeFile(bodyFile, makeBody(spec, stats, options, env.arguments, pandoc = true)) >>
      warn(pandoc.executable.path+" "+pandocArguments.mkString(" ")).when(pandoc.verbose) >>
      Executable.run(pandoc.executable, pandocArguments) >>
      replaceInFile(outputPath(options.outDir, spec), "<code>", "<code class=\"prettyprint\">")
    }
  }

  def copyResources(env: Env, options: HtmlOptions): Action[List[Unit]] =
    env.fileSystem.mkdirs(options.outDir) >> {
      List(DirectoryPath("css"),
           DirectoryPath("javascript"),
           DirectoryPath("images"),
           DirectoryPath("templates")).
           map(copySpecResourcesDir(env, "org" / "specs2" / "reporter", options.outDir, classOf[HtmlPrinter].getClassLoader))
        .sequenceU
        .whenFailed((e: String \&/ Throwable) => warnAndFail("Cannot copy resources to "+options.outDir.path+"\n"+Status.asString(e), RunAborted))
    }

  def copySpecResourcesDir(env: Env, base: DirectoryPath, outputDir: DirectoryPath, loader: ClassLoader)(src: DirectoryPath): Action[Unit] = {
    Option(loader.getResource((base / src).path)) match {
      case None =>
        warnAndFail(s"no resource found for url ${(base / src).path}", RunAborted)

      case Some(url) =>
        val fs = env.fileSystem
        if (url.getProtocol.equalsIgnoreCase("jar"))
          fs.unjar(jarOf(url), outputDir, s"^${quote(base.path)}(/${quote(src.path)}/.*)$$")
        else
          fs.copyDir(DirectoryPath.unsafe(url.toURI), outputDir / src)
    }
  }

  def reportMissingSeeRefs(specs: List[SpecStructure], outDir: DirectoryPath): Action[Unit] = for {
    missingSeeRefs <- specs.flatMap(_.seeReferences).distinct.filterM(ref => FilePathReader.doesNotExist(SpecHtmlPage.outputPath(outDir, ref.specClassName)))
    _              <- warn("The following specifications are being referenced but haven't been reported\n"+
                           missingSeeRefs.map(_.specClassName).distinct.mkString("\n")).unless(missingSeeRefs.isEmpty)
  } yield ()

  private def jarOf(url: URL): URL = url.openConnection.asInstanceOf[JarURLConnection].getJarFileURL

  val RunAborted =
    "\nHtml run aborted!\n "
}

object HtmlPrinter extends HtmlPrinter
