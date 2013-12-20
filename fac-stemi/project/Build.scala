import sbt._
import Keys._

object ApplicationBuild extends Build {

   val appName = "fac-stemi"
   val appVersion = "1.0-SNAPSHOT"

   val appDependencies = Seq(
      "org.xhtmlrenderer" % "flying-saucer-pdf" % "9.0.4",
      "net.sf.jtidy" % "jtidy" % "r938",
      "com.google.apis" % "google-api-services-drive" % "v2-rev109-1.16.0-rc",
      "com.google.http-client" % "google-http-client-jackson2" % "1.17.0-rc",
      "org.scalatest" %% "scalatest" % "1.9.1" % "test"
   )


   val main = play.Project(appName, appVersion, appDependencies).settings(
      
   )
}
