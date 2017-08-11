# easy-sm
A simple command line program for listing all the transient dependencies of a library in apache buildr format

Why would you want to do that? To make it easier to create your dependencies for tools such as [SM](http://github.com/bodar/shavenmaven)

# Example

You want to include hamcrest-junit in your library:sparkles:. You need to get hamcrest and all its transient dependencies 
so that they can be put in the dependencies file that SM will use to retrieve you dependencies.

```
> java -jar easy-sm.jar -dependency org.hamcrest hamcrest-junit 2.0.0.0
```

which will print to stdout

```
mvn:org.hamcrest:hamcrest-junit:jar:2.0.0.0
mvn:junit:junit:jar:4.12
mvn:org.hamcrest:java-hamcrest:jar:2.0.0.0
mvn:org.hamcrest:hamcrest-core:jar:1.3
```

And that's it! pipe that to a file and you've got your dependencies. You may want to sort it, and then merge 
it with other dependencies to see what kind of conflicts you get, but that's up to you. This tool is just to get you started.

:sparkles: though really you shouldn't, just use junit and the latest version of hamcrest directly. I don't know what the 
hell hamcrest-junit is at all, but it made for a small, but not tiny, example.

# More info

So the most common format is

```
> java -jar easy-sm.jar -dependency [groupId] [artifactId] [version]
```

But I copied this stuff from the Ivy code:sparkles: so there are a bunch of options I don't understand and don't 
know if they will work. Give it a go if you want! let me know how it goes for you. I will probably ignore you as
I suspect I don't care.

```
usage: ivy
==== settings options
 -settings <settingsfile>     use given file for settings
 -cache <cachedir>            use given directory for cache
 -novalidate                  do not validate ivy files against xsd
 -m2compatible                use Maven 2 compatibility

==== resolve options
 -ivy <ivyfile>               use given file as ivy file
 -refresh                     refresh dynamic resolved revisions
 -dependency <organisation> <module> <revision>
                              use this instead of ivy file to do the rest of the
                               work with this as a dependency.
 -confs <configurations>      resolve given configurations
 -types <types>               accepted artifact types
 -mode <resolvemode>          the resolve mode to use
 -notransitive                do not resolve dependencies transitively

==== deliver options
 -deliverto <ivypattern>      use given pattern as resolved ivy file pattern

==== http auth options
 -realm <realm>               use given realm for HTTP AUTH
 -host <host>                 use given host for HTTP AUTH
 -username <username>         use given username for HTTP AUTH
 -passwd <passwd>             use given password for HTTP AUTH

==== message options
 -debug                       set message level to debug
 -verbose                     set message level to verbose
 -warn                        set message level to warn
 -error                       set message level to error

==== help options
 -?                           display this help
 -deprecated                  show deprecated options
 -version                     displays version information
```

:sparkles: Oh, did I mention that this uses Ivy under the hood to do the heavy lifting, and the one class that 
I wrote is a complete rip off of ant-ivy/src/java/org/apache/ivy/Main.java in (https://github.com/apache/ant-ivy)? 
I didn't? Well, I have now...

# Build

easy peasey - it's just gradle.

```
> ../graldew shadowjar
```

That will give you an `easy-sm.jar` in `build/lib`. Go ahead and use it!

