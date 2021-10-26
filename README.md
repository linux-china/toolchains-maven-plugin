Toolchains Maven Plugin
=========================

Extend maven-toolchains-plugin to add JDK auto download and toolchains.xml management.

# Features
                 
* JDK auto download with Foojay API support
* Add new toolchain into toolchains.xml dynamically
          
# Requirements

* Maven 3.5+
* JDK 1.7+

# How to use?

Add following plugin configuration to your pom.xml:   

```xml
    <plugin>
      <groupId>org.mvnsearch</groupId>
      <artifactId>toolchains-maven-plugin</artifactId>
      <version>4.0.0-SNAPSHOT</version>
      <executions>
          <execution>
              <goals>
                  <goal>toolchain</goal>
              </goals>
          </execution>
      </executions>
      <configuration>
          <toolchains>
              <jdk>
                  <version>17</version>
              </jdk>
          </toolchains>
      </configuration>
    </plugin>
```
    
# How to skip toolchains maven plugin on CI/CD platform?

```
$ mvn -Dtoolchain.skip -DskipTests package
```

# References

* Apache Maven Toolchains Plugin: https://maven.apache.org/plugins/maven-toolchains-plugin/
* foojay DiscoAPI: https://api.foojay.io/swagger-ui/