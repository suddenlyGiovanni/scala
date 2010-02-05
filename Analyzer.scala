/* sbt -- Simple Build Tool
 * Copyright 2008, 2009 Mark Harrah
 */
package xsbt

import scala.tools.nsc.{io, plugins, symtab, Global, Phase}
import io.{AbstractFile, PlainFile, ZipArchive}
import plugins.{Plugin, PluginComponent}
import symtab.Flags
import scala.collection.mutable.{HashMap, HashSet, Map, Set}

import java.io.File
import xsbti.AnalysisCallback

object Analyzer
{
	def name = "xsbt-analyzer"
}
final class Analyzer(val global: Global, val callback: AnalysisCallback) extends NotNull
{
	import global._

	/** After 2.8.0.Beta1, fullNameString was renamed fullName.*/
	private implicit def symName(sym: Symbol): WithString = new WithString(sym)
	private final class WithString(s: Symbol)
	{
		def fullNameString = s.fullName; def fullName = sourceCompatibilityOnly
		def fullNameString(sep: Char) = s.fullName(sep); def fullName(sep: Char) = sourceCompatibilityOnly
		private def sourceCompatibilityOnly = error("For source compatibility only: should not get here.")
	}

	def newPhase(prev: Phase): Phase = new AnalyzerPhase(prev)
	private class AnalyzerPhase(prev: Phase) extends Phase(prev)
	{
		override def description = "Extracts dependency information, finds concrete instances of provided superclasses, and application entry points."
		def name = Analyzer.name
		def run
		{
			val outputDirectory = new File(global.settings.outdir.value)
			val superclasses = callback.superclassNames flatMap(classForName)

			for(unit <- currentRun.units)
			{
				// build dependencies structure
				val sourceFile = unit.source.file.file
				callback.beginSource(sourceFile)
				for(on <- unit.depends)
				{
					val onSource = on.sourceFile
					if(onSource == null)
					{
						classFile(on) match
						{
							case Some(f) =>
							{
								f match
								{
									case ze: ZipArchive#Entry => callback.jarDependency(new File(ze.getArchive.getName), sourceFile)
									case pf: PlainFile => callback.classDependency(pf.file, sourceFile)
									case _ => ()
								}
							}
							case None => ()
						}
					}
					else
						callback.sourceDependency(onSource.file, sourceFile)
				}

				// find subclasses and modules with main methods
				for(clazz @ ClassDef(mods, n, _, _) <- unit.body)
				{
					val sym = clazz.symbol
					if(sym != NoSymbol && mods.isPublic && !mods.isAbstract && !mods.isTrait &&
						 !sym.isImplClass && sym.isStatic && !sym.isNestedClass)
					{
						val isModule = sym.isModuleClass
						for(superclass <- superclasses.filter(sym.isSubClass))
							callback.foundSubclass(sourceFile, sym.fullNameString, superclass.fullNameString, isModule)
						if(isModule && hasMainMethod(sym))
							callback.foundApplication(sourceFile, sym.fullNameString)
					}
				}

				// build list of generated classes
				for(iclass <- unit.icode)
				{
					val sym = iclass.symbol
					def addGenerated(separatorRequired: Boolean)
					{
						val classFile = fileForClass(outputDirectory, sym, separatorRequired)
						if(classFile.exists)
							callback.generatedClass(sourceFile, classFile)
					}
					if(sym.isModuleClass && !sym.isImplClass)
					{
						if(isTopLevelModule(sym) && sym.linkedClassOfModule == NoSymbol)
							addGenerated(false)
						addGenerated(true)
					}
					else
						addGenerated(false)
				}
				callback.endSource(sourceFile)
			}
		}
	}

	private def classForName(name: String) =
	{
		try
		{
			if(name.indexOf('.') < 0)
			{
				val sym = definitions.EmptyPackageClass.info.member(newTypeName(name))
				if(sym != NoSymbol) Some( sym ) else { callback.superclassNotFound(name); None }
			}
			else
				Some( global.definitions.getClass(newTermName(name)) )
		}
		catch { case fe: scala.tools.nsc.FatalError =>  callback.superclassNotFound(name); None }
	}
	private def classFile(sym: Symbol): Option[AbstractFile] =
	{
		import scala.tools.nsc.symtab.Flags
		val name = flatname(sym, finder.classSeparator) + moduleSuffix(sym)
		finder.findClass(name) orElse {
			if(isTopLevelModule(sym))
			{
				val linked = sym.linkedClassOfModule
				if(linked == NoSymbol)
					None
				else
					classFile(linked)
			}
			else
				None
		}
	}

	// doesn't seem to be in 2.7.7, so copied from GenJVM to here
	private def moduleSuffix(sym: Symbol) =
		if (sym.hasFlag(Flags.MODULE) && !sym.isMethod && !sym.isImplClass && !sym.hasFlag(Flags.JAVA)) "$" else "";
	private def flatname(s: Symbol, separator: Char) =
		atPhase(currentRun.flattenPhase.next) { s.fullNameString(separator) }

	private def isTopLevelModule(sym: Symbol): Boolean =
		atPhase (currentRun.picklerPhase.next) {
			sym.isModuleClass && !sym.isImplClass && !sym.isNestedClass
		}
	private def fileForClass(outputDirectory: File, s: Symbol, separatorRequired: Boolean): File =
		new File(outputDirectory, flatname(s, File.separatorChar) + (if(separatorRequired) "$" else "") + ".class")

	private def hasMainMethod(sym: Symbol): Boolean =
	{
		val main = sym.info.nonPrivateMember(newTermName("main"))//nme.main)
		atPhase(currentRun.typerPhase.next) {
			main.tpe match
			{
				case OverloadedType(pre, alternatives) => alternatives.exists(alt => isVisible(alt) && isMainType(pre.memberType(alt)))
				case tpe => isVisible(main) && isMainType(main.owner.thisType.memberType(main))
			}
		}
	}
	private def isVisible(sym: Symbol) = sym != NoSymbol && sym.isPublic && !sym.isDeferred
	private def isMainType(tpe: Type): Boolean =
		tpe match
		{
			// singleArgument is of type Symbol in 2.8.0 and type Type in 2.7.x
			case MethodType(List(singleArgument), result) => isUnitType(result) && isStringArray(singleArgument)
			case PolyType(typeParams, result) => isMainType(result)
			case _ =>  false
		}
	private lazy val StringArrayType = appliedType(definitions.ArrayClass.typeConstructor, definitions.StringClass.tpe :: Nil)
	// isStringArray is overloaded to handle the incompatibility between 2.7.x and 2.8.0
	private def isStringArray(tpe: Type): Boolean =
		tpe =:= StringArrayType ||
		// needed for main defined in parent trait, not sure why
		tpe.typeSymbol == definitions.ArrayClass && tpe.typeArgs.length == 1 && tpe.typeArgs(0).typeSymbol == definitions.StringClass
	private def isStringArray(sym: Symbol): Boolean = isStringArray(sym.tpe)
	private def isUnitType(tpe: Type) = tpe.typeSymbol == definitions.UnitClass
	
	// required because the 2.8 way to find a class is:
	//   classPath.findClass(name).flatMap(_.binary)
	// and the 2.7 way is:
	//   val entry = classPath.root.find(name, false)
	//   if(entry eq null) None else Some(entry.classFile)
	private lazy val finder = try { new LegacyFinder } catch { case _ => new NewFinder }
	private trait ClassFinder
	{
		def classSeparator: Char
		def findClass(name: String): Option[AbstractFile]
	}
	private class NewFinder extends ClassFinder
	{
		class Compat27 { def findClass(name: String) = this; def flatMap(f: Compat27 => AnyRef) = Predef.error("Should never be called"); def binary = None }
		implicit def compat27(any: AnyRef): Compat27 = new Compat27

		def classSeparator = '.' // 2.8 uses . when searching for classes
		def findClass(name: String): Option[AbstractFile] =
			classPath.findClass(name).flatMap(_.binary.asInstanceOf[Option[AbstractFile]])
	}
	private class LegacyFinder extends ClassFinder
	{
		class Compat28 { def root: Compat28 = invalid; def find(n: String, b: Boolean) = this; def classFile = invalid; def invalid = Predef.error("Should never be called") }
		implicit def compat28(any: AnyRef): Compat28 = new Compat28

		def classSeparator = File.separatorChar // 2.7 uses / or \ when searching for classes
		private val root = classPath.root
		def findClass(name: String): Option[AbstractFile] =
		{
			val entry = root.find(name, false)
			if(entry eq null) None else Some(entry.classFile)
		}
	}
}