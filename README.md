# install-if-not-exist

Check if a maven artifact exists in the local or remote repository. If it is not found installs the file given.

## Goals
There is just one goal: install-file-if-not-exist

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
|file               |Required value     |Path to the file to install|
|groupId            |Required value     |The group of the artifact to look for and install|
|artifactId         |Required value     |The artifact ID of the artifact to look for and install|
|version            |Required value     |The version of the artifact to look for and install|
|classifier         |null               |The classifier of the artifact to look for and install|
|packaging          |The extension of the file given in the file parameter|The packaging of the artifact (pom,jar,war...)|



## Requirements
- Maven 3.5 or later
- Java 1.8 or later

## Typical Use

```xml
 <plugin>
     <groupId>org.franjavi</groupId>
     <artifactId>install-if-not-exist</artifactId>
     <version>1.0-SNAPSHOT</version>
     <configuration>
       <file>${project.basedir}/src/main/resources/dummy.jar</file>
       <groupId>com.project</groupId>
       <artifactId>artifactName</artifactId>
       <classifier>optionalSample</classifier>
       <version>2.0-SNAPSHOT</version>
     </configuration>
     <executions>
       <execution>
         <phase>install</phase>
         <goals>
           <goal>install-file-if-not-exist</goal>
         </goals>
       </execution>
     </executions>
</plugin>
```
