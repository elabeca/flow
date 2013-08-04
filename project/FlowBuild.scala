import sbt._
import Keys._

import NativeKeys._
import NativeDefaults._
import JniKeys._

object FlowBuild extends Build {
  val Organization = "com.github.jodersky"
  val Version = "1.1-SNAPSHOT" //version of flow library
  val BinaryMajorVersion = 2 //binary major version used to select shared libraries when publishing (needs to be incremented if API changes are made to flow.h or NativeSerial.java)
  val ScalaVersion = "2.10.2"
  //see native settings down below
  
  lazy val commonSettings: Seq[Setting[_]] = Seq(
    organization := Organization,
    version := Version,
    scalaVersion := ScalaVersion,
    licenses := Seq(("BSD-3-Clause", url("http://opensource.org/licenses/BSD-3-Clause"))),
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature"))
  
  lazy val runSettings: Seq[Setting[_]] = Seq(
    fork := true,
    connectInput in run := true,
    outputStrategy := Some(StdoutOutput)
  )
  
  lazy val main: Project = (
    Project("flow", file("flow-main"))
    settings (commonSettings: _*)
    settings (
      libraryDependencies ++= Seq(
        Dependencies.akkaActor,
        Dependencies.ioCore,
        Dependencies.ioFile), 
      compileOrder in Compile := CompileOrder.Mixed,
      resourceGenerators in Compile <+= (resourceManaged in Compile, binariesDirectory in ThisBuild) map { (resDir, binDir) =>
        val binaries: Seq[(File, File)] = getLatestBinaries(binDir, BinaryMajorVersion)
        val resources = for (binary <- binaries) yield {
          val versionedBinary = binary._1
          val unversionedBinary = binary._2
          
          val relative = (unversionedBinary relativeTo binDir).get.getPath
          
          val resource = resDir / "native" / relative
          IO.copyFile(versionedBinary, resource)
          resource
        }
        resources
      }
    )
  )
  
  //returns latest, major version-compatible binaries for every OS and architecture combination
  //tuples returned (file with binary version appended, file without version string)
  def getLatestBinaries(base: File, majorVersion: Int): Seq[(File, File)] = {
    def latest(platform: File) = {
      val Pattern = "(.+)\\.(\\d+)\\.(\\d+)".r
      val MajorVersion = majorVersion.toString
      val majorCompatible = platform.listFiles.map(_.getAbsolutePath) collect {
        case path @ Pattern(strippedPath, MajorVersion, minorVersion) => (path, strippedPath, minorVersion)
      }
      val latestMinor = majorCompatible.sortBy(_._3).lastOption
      latestMinor map { case (path, strippedPath, _) =>
        (file(path), file(strippedPath))
      }
    }
    
    val oSs = IO.listFiles(base).filter(file => !file.name.startsWith("."))
    val platforms = oSs.flatMap(_.listFiles)
    
    platforms.flatMap(latest(_))
  }
  
  lazy val samplesTerminal = (
    Project("flow-samples-terminal", file("flow-samples") / "terminal")
    settings(commonSettings: _*)
    settings(runSettings: _*)
    dependsOn(main)
  )
  
 
  //--- native settings and projects --------------------------------------------------
  
  val binariesDirectory = settingKey[File]("Directory containing published native binaries.")
  override lazy val settings = super.settings ++ Seq(
    (binariesDirectory in ThisBuild) := (baseDirectory in ThisBuild).value / "flow-binaries"
  )
    
  def canonicalBinaryPath(base: File, binaryName: String) = {
	base / sys.props("os.name").toLowerCase.filter(_ != ' ') / sys.props("os.arch").toLowerCase / binaryName    
  }
  val publishNative = taskKey[File]("Publish native binary compiled on current OS to flow-binaries project so that it may be packaged in a distribution of flow.")
  val publishNativeImpl = Def.task{
    val in = (link in Native).value
    val out = canonicalBinaryPath((binariesDirectory in ThisBuild).value, in.getName)
    IO.copyFile(in, out)
    out
  }

  lazy val commonNativeSettings: Seq[Setting[_]] = Seq(
    nativeSource in Native := baseDirectory.value / "src",
    includeDirectories in Native += file("flow-native") / "shared" / "include",
    nativeCompile in Native := ((nativeCompile in Native) dependsOn (compile in Compile in main)).value,
    publishNative := publishNativeImpl.value,
    javahClasspath := Seq((classDirectory in Compile in main).value),
    javahClasses := Seq("com.github.jodersky.flow.internal.NativeSerial")) ++ JniDefaults.defaultSettings

    
  //--- native unix-like settings and projects ----------------------------------------
  
  val UnixBinaryMinorVersion = 1
  
  lazy val unixNativeSettings: Seq[Setting[_]] = commonNativeSettings ++ Seq(
    flags in Native := Seq("-fPIC", "-O2")
    )

  lazy val nativeLinux = (
    NativeProject("flow-native-linux", file("flow-native") / "unix")
    settings (unixNativeSettings: _*)
    settings (
      target := baseDirectory.value / "target" / "linux",
      includeDirectories in Native ++= jdkHome.value.map(jdk => jdk / "include" / "linux").toSeq,
      linkFlags in Native ++= Seq("-shared", s"-Wl,-soname,libflow.so.${BinaryMajorVersion}"),
      binaryName in Native := s"libflow.so.${BinaryMajorVersion}.${UnixBinaryMinorVersion}"
    )
    dependsOn(main)
  )
  
  lazy val nativeMacOSX = (
    NativeProject("flow-native-macosx", file("flow-native") / "unix")
    settings (unixNativeSettings: _*)
    settings (
      target := baseDirectory.value / "target" / "macosx",
      includeDirectories in Native += file("/System/Library/Frameworks/JavaVM.framework/Headers/jni.h"),
      includeDirectories in Native += file("/Developer/SDKs/MacOSX10.6.sdk/System/Library/Frameworks/JavaVM.framework/Versions/A/Headers"),
      linkFlags in Native ++= Seq("-dynamiclib"),
      binaryName in Native := s"libflow.jnilib.${BinaryMajorVersion}.${UnixBinaryMinorVersion}"
    )
    dependsOn (main)
  )
  
  
  /* stub for native project on windows, I don't know if this would actually work...
   * 
   * val WindowsBinaryMinorVersion = 0
   * 
  lazy val nativeWindows = (
    NativeProject("flow-native-windows", file("flow-native") / "windows")
    settings (
      //windows is not a unix-like OS, several default settings need to be changed
      cCompiler in Native := "???",
      flags in Native := Seq("-fPIC", "-O2"),
      linkFlags in Native ++= ???,
      binaryName in Native := s"flow.dll.${BinaryMajorVersion}.${WindowsBinaryMinorVersion}"
      includeDirectories in Native += jdkHome.value / "include" / "windows"
    )
    dependsOn (main)
  )*/
  
}
