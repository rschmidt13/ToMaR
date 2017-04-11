SCAPE Tool-to-MapReduce Wrapper (ToMaR)
====================================== 
*Let your Cmd-line Tools Scale*

This Repository
---------------
The original version of ToMaR for Java 7 and CDH4.2 can be found <a href="https://github.com/openpreserve/ToMaR">here</a> on Github. This repository provides a version of ToMar for Java 8 and later CDH versions.


About
-----
ToMaR supports the use of legacy applications in a MapReduce environment by providing third party tools as User Defined Functions. The application specifically addresses the need for processing large volumes of binary content using existing, content-specific applications. ToMaR provides a generic MapReduce wrapper that can be use with command-line and Java applications. It supports tools that read input based on local file pointers or stdin/stdout streams. ToMaR implements a custom InputFormat to take advantage of data locality and can be used both, as a MapReduce application or as part of an Apache PIG script. More <a href="http://www.scape-project.eu/wp-content/uploads/2014/08/SCAPE_D5.3_AIT_V1.0.pdf">documentation</a> can be found on the SCAPE project web site.   

Installation and Use
--------------------

### Installation

Simply `git clone` the repository and run `mvn install`. The Hadoop executable jar can be found in `target/tomar-*-with-dependencies.jar`.


### Prerequisites

* A running Hadoop 1.0.x installation (either standalone, pseudo-distributed or cluster)
* SCAPE Toolspecs on HDFS and tools installed on each node
* The control file (see below for details)

### How to use 

    hadoop jar {path-to-jar} 
        -i {control-file} 
        -o {output-dir-for-job} 
        -r {toolspec-repo-dir}
        -n {lines-per-split}

* *path-to-jar* leads to the jar file of the SCAPE ToMaR
* *control-file* located on HDFS
* *output-dir-for-job* is the directory on HDFS where output files will be written to. Default is `out/{some random number}`
* *toolspec-repo-dir* is a directory on HDFS containing available toolspecs
* *lines-per-split* configures the number of lines each mapper (worker node) will receive for processing, default is 10

Additionally, you can specify generic options for the Hadoop job, eg. for a custom input format or a reducer class, described [here](http://hadoop.apache.org/docs/r1.2.1/commands_manual.html#Generic+Options).

### The Control File

ToMaR consumes a plain text control file which describes one tool invocation line per line.
A _control line_ consists of a pair of a _toolspec_ name and an _action_ of that toolspec. An _action_ is associated with a specific shell command pattern by the _toolspec_.

Beneath the _toolspec-action_ pair a _control line_ may contain additional parameters for the _action_. These are mapped to the placeholders in the definition of the _action_. Parameters are specified by a list of --{placeholder}="{value}" strings after the _toolspec-action_ pair. For example:

    fancy-tool do-fancy-thing --fancy-parameter="foo" --another-fancy-parameter="bar"

For this _control line_ to function there should be a _toolspec_ named `fancy-tool` containing the _action_ `do-fancy-thing`, which should have `fancy-parameter` and `another-fancy-parameter` defined in its parameters section. An action's input and output file parameters are specified the same way. For example: 

    fancy-tool do-fancy-file-stuff --input="hdfs:///fancy-file.foo" --output="hdfs:///fancy-output-file.bar"

Again, an input parameter `input` and an output parameter `output` needs to be defined in the correspondent sections of `do-fancy-file-stuff`.

**Don't use the character '_' (underscore) in toolspec, action or key names. It is allowed to use it in values within quotes.**

#### File redirection and piping

As an _action_'s command may be reading from standard input and/or writing to standard output, a _stdin_ and/or _stdout_ section should be defined for the _action_. From the _control line_'s perspective these properties are mapped by the `>` character. For example:

    "hdfs:///input-file.foo" > fancy-tool do-fancy-streaming > "hdfs:///output-file.bar"

Prior to the execution of the _action_, the wrapper will start reading an input stream of `hdfs:///input-file.foo` and feeding its contents to the command of `do-fancy-streaming`. Respectively, the output is redirected to an output stream of `hdfs:///output-file.bar`.

Instead of streaming the command's output to a file, it could be streamed to another _action_ of another _toolspec_ imitating pipes in the UNIX shell. For example:

    "hdfs:///input-file.foo" > fancy-tool do-fancy-streaming | funny-tool do-funny-streaming > "hdfs:///output-file.bar"

This _control line_ results in the output of the command of `do-fancy-streaming` being piped to the command of `do-funny-streaming`. Then the output of the latter one will be redirected to `hdfs:///output-file.bar`. 

There can be numerous pipes in one _control line_ but only one input file at the beginning and one output file at the end for file redirection. Independently from this, the piped _toolspec-action_ pairs may contain parameters as explained in the previous section, ie. input and output file parameters too.

If a _control line_ produces standard output and there is not final redirection to an output file, then the output is written to Hadoop default output file `part-r-00000`. It contains the Job's output key-value pairs. Key is the hashcode of the _control line_.


### Example

As an example the execution of ToMaR on 

1. file identification 
2. streamed file identification
3. postscript to PDF migration of an input ps-file to an output pdf-file
4. streamed in postscript to PDF migration of an input ps-file to an streamed out pdf-file
5. streamed in ps-to-pdf migration with consecutive piped file identification
6. streamed in ps-to-pdf migration with two consecutive piped file identifications

is described and demonstrated in this section. The input _control file_ used in this example only contains one control line each. Of course in a productive environment one would have thousands of such control lines.

#### Prerequisites

1. Make sure the commands `file` and `ps2pdf` are in the path of your system. 
2. Copy the toolspecs [file.xml](https://github.com/openplanets/tomar/tree/master/src/test/resources/toolspecs/file.xml) and [ps2pdf.xml](https://github.com/openplanets/tomar/tree/master/src/test/resources/toolspecs/ps2pdf.xml) to a directory of your choice on HDFS (eg. `/user/you/toolspecs/`).
3. Copy [ps2pdf-input.ps](https://github.com/openplanets/tomar/tree/master/src/test/resources/ps2pdf-input.ps) to a directory of your choice on HDFS (eg. `/user/you/input/`).

#### File identification 

Contents of the control file:

    file identify --input="hdfs:///user/you/input/ps2pdf-input.ps"

After running the job, contents of `part-r-00000` in output directory is:

    0      PostScript document text conforming DSC level 3.0, Level 2

#### Streamed file identification   

Contents of the control file:

    "hdfs:///user/you/input/ps2pdf-input.ps" > file identify-stdin 

After running the job, contents of `part-r-00000` in output directory is:

    0      PostScript document text conforming DSC level 3.0, Level 2

#### Postscript to PDF migration

Contents of the control file:

    ps2pdf convert --input="hdfs:///user/you/input/ps2pdf-input.ps" --output="hdfs:///user/you/output/ps2pdf-output.pdf"

After running the job, specified output file location references the migrated PDF.

#### Streamed postscript to PDF migration

Contents of the control file:

    "hdfs:///user/you/input/ps2pdf-input.ps" > ps2pdf convert-streamed > "hdfs:///user/you/output/ps2pdf-output.pdf"

After running the job, specified output file location references the migrated PDF.

#### Streamed postscript to PDF migration with consecutive piped file identification

Contents of the control file:

    "hdfs:///user/you/input/ps2pdf-input.ps" > ps2pdf convert-streamed | file identify-stdin > "hdfs:///user/you/output/file-identified.txt" 

After running the job, contents of `file-identified.txt` in output directory is:

    PDF document, version 1.4    

#### Streamed postscript to PDF migration with two consecutive piped file identifications

Contents of the control file:

    "hdfs:///user/you/input/ps2pdf-input.ps" > ps2pdf convert-streamed | file identify-stdin | file identify-stdin

After running the job, contents of `part-r-00000` in output directory is:

    0     ASCII text

