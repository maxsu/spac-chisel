ThisBuild / scalaVersion     := "2.13.12"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "dev.spac"

val chiseltestVersion = "0.6.2"

lazy val root = (project in file("."))
  .settings(
    name := "spac-chisel",
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3"            % "3.6.1",
      "edu.berkeley.cs" %% "chiseltest"         % chiseltestVersion % Test,
      "org.scalanlp"    %% "breeze"             % "2.1.0",
      "com.github.tototoshi" %% "scala-csv"     % "1.3.10",
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
    ),
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.6.1" cross CrossVersion.full),
    // test output
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  )
