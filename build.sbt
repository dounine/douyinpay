import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy}

val akkaVersion = "2.6.15"
val akkaHttpVersion = "10.2.4"
val json4sVersion = "3.7.0-M6"
val alpakka = "2.0.2"
val elastic4sVersion = "7.12.0"

lazy val app = (project in file("."))
  .enablePlugins(DockerPlugin, UniversalPlugin, JavaServerAppPackaging)
  .settings(
    name := "douyinpay",
    maintainer := "amwoqmgo@gmail.com",
    scalaVersion := "2.13.4",
    dockerRepository := Some("dounine"),
    version := "1.0.0",
    dockerUsername := sys.props.get("docker.username"),
    dockerRepository := Some("dounine"),
    dockerBaseImage := "openjdk:11.0.8-slim",
    dockerExposedPorts := Seq(30000, 33333),
    dockerEnvVars := Map("apiVersion" -> "1.0.0"),
    dockerEntrypoint := Seq("/opt/docker/bin/douyinpay"),
    dockerPermissionStrategy := DockerPermissionStrategy.Run,
    javaOptions in Universal += Seq(
      "-Dcom.sun.management.jmxremote=true",
      "-Djava.rmi.server.hostname=douyinpay",
      "-Dcom.sun.management.jmxremote.port=33333",
      "-Dcom.sun.management.jmxremote.rmi.port=33333",
      "-Dcom.sun.management.jmxremote.ssl=false",
      "-Dcom.sun.management.jmxremote.authenticate=false"
    ).mkString(" "),
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      Cmd("RUN", "ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime"),
      Cmd("RUN", "echo 'Asia/Shanghai' > /etc/timezone")
    ),
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4sVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.github.pureconfig" %% "pureconfig" % "0.12.3",
      "eu.timepit" %% "refined" % "0.9.24",
      "eu.timepit" %% "refined-cats" % "0.9.24",
      "eu.timepit" %% "refined-eval" % "0.9.24",
      "com.lightbend.akka" %% "akka-stream-alpakka-file" % alpakka,
      "com.lightbend.akka" %% "akka-stream-alpakka-slick" % alpakka,
      "com.lightbend.akka" %% "akka-stream-alpakka-udp" % alpakka,
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-caching" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "org.apache.commons" % "commons-pool2" % "2.9.0",
      "ch.megard" %% "akka-http-cors" % "1.1.1",
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
      "com.lightbend.akka" %% "akka-persistence-jdbc" % "4.0.0",
      "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
      "org.json4s" %% "json4s-jackson" % json4sVersion,
      "org.json4s" %% "json4s-native" % json4sVersion,
      "org.json4s" %% "json4s-ext" % json4sVersion,
      "org.sangria-graphql" %% "sangria" % "2.1.3",
      "org.sangria-graphql" %% "sangria-circe" % "1.3.1",
      "org.sangria-graphql" %% "sangria-slowlog" % "2.0.1",
      "org.sangria-graphql" %% "sangria-akka-http-core" % "0.0.2",
      "org.sangria-graphql" %% "sangria-akka-http-circe" % "0.0.2",
      "org.seleniumhq.selenium" % "selenium-java" % "3.141.59",
      "com.google.zxing" % "core" % "3.4.1",
      "com.google.zxing" % "javase" % "3.4.1",
      "com.lightbend.akka.management" %% "akka-lease-kubernetes" % "1.0.9",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "1.0.9",
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % "1.0.9",
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "1.0.9",
      "com.github.pathikrit" %% "better-files" % "3.9.1",
      "com.github.pathikrit" %% "better-files-akka" % "3.9.1",
      "com.chuusai" %% "shapeless" % "2.3.3",
      "io.underscore" %% "slickless" % "0.3.6",
      "io.altoo" %% "akka-kryo-serialization" % "2.0.1",
      "de.heikoseeberger" %% "akka-http-circe" % "1.36.0",
      "de.heikoseeberger" %% "akka-http-json4s" % "1.36.0",
      "com.typesafe.akka" %% "akka-http-xml" % akkaHttpVersion,
      "org.typelevel" %% "cats-core" % "2.6.0",
      "io.circe" %% "circe-refined" % "0.13.0",
      "io.circe" %% "circe-generic" % "0.13.0",
      "io.circe" %% "circe-core" % "0.13.0",
      "io.circe" %% "circe-parser" % "0.13.0",
      "io.circe" %% "circe-optics" % "0.13.0",
      "com.esotericsoftware" % "kryo" % "5.0.3",
      "mysql" % "mysql-connector-java" % "8.0.22",
      "commons-codec" % "commons-codec" % "1.15",
      "commons-cli" % "commons-cli" % "1.4",
      "redis.clients" % "jedis" % "3.3.0",
      "com.typesafe.slick" %% "slick-hikaricp" % "3.3.3",
      "com.typesafe.slick" %% "slick-codegen" % "3.3.3",
      "io.spray" %% "spray-json" % "1.3.6",
      "com.pauldijou" %% "jwt-core" % "4.3.0",
      "com.holidaycheck" %% "easy-akka-marshalling" % "1.0.0",
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "org.scalatestplus" %% "mockito-3-4" % "3.2.5.0" % Test,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "org.scalatest" %% "scalatest" % "3.1.2" % Test,
      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8" % Test,
      "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4sVersion % Test
    )
  )
