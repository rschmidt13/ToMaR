package eu.scape_project.pt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import eu.scape_project.pt.proc.Processor;
import eu.scape_project.pt.proc.StreamProcessor;
import eu.scape_project.pt.proc.ToolProcessor;
import eu.scape_project.pt.repo.Repository;
import eu.scape_project.pt.repo.ToolRepository;
import eu.scape_project.pt.util.CmdLineParser;
import eu.scape_project.pt.util.Command;
import eu.scape_project.pt.util.PipedArgsParser;
import eu.scape_project.pt.util.PropertyNames;
import eu.scape_project.pt.util.fs.Filer;
import eu.scape_project.tool.toolwrapper.data.tool_spec.Operation;
import eu.scape_project.tool.toolwrapper.data.tool_spec.Tool;


public class ToolWrapper {
    private static final String SEP = " ";

    private CmdLineParser parser;
    private Repository repo;
    private Tool tool;
    private Operation operation;

    private final Log LOG = LogFactory.getLog(ToolWrapper.class);

    /**
     * Sets up toolspec repository and parser.
     */
    //public void setup(Context context) throws IOException {
    public void setup(Configuration conf) throws IOException {
        String strRepo = conf.get(PropertyNames.REPO_LOCATION);
        Path fRepo = new Path(strRepo);
        FileSystem fs = FileSystem.get(conf);
        this.repo = new ToolRepository(fs, fRepo);

        // create parser of command line input arguments
        parser = new PipedArgsParser();
    }

    public String wrap(String controlline) throws Exception {
        // parse input line for stdin/out file refs and tool/action commands
        parser.parse(controlline);

        final Command[] commands = parser.getCommands();
        final String strStdinFile = parser.getStdinFile();
        final String strStdoutFile = parser.getStdoutFile();

        Processor firstProcessor = null;
        ToolProcessor lastProcessor = null; 

        Map<String, String>[] mapOutputFileParameters = new HashMap[commands.length];

        for(int c = 0; c < commands.length; c++ ) {
            Command command = commands[c];

            tool = repo.getTool(command.getTool());

            lastProcessor = new ToolProcessor(tool);

            operation = lastProcessor.findOperation(command.getAction());
            if( operation == null )
                throw new IOException(
                        "operation " + command.getAction() + " not found");

            lastProcessor.setOperation(operation);

            lastProcessor.initialize();

            lastProcessor.setParameters(command.getPairs());
            lastProcessor.setWorkingDir(workingDir());

            // get parameters accepted by the lastProcessor.
            Map<String, String> mapInputFileParameters = lastProcessor.getInputFileParameters(); 
            mapOutputFileParameters[c] = lastProcessor.getOutputFileParameters(); 

            // copy parameters to temporal map
            Map<String, String> mapTempInputFileParameters = 
                new HashMap<String, String>(mapInputFileParameters);

            // localize parameters
            for( Entry<String, String> entry : mapInputFileParameters.entrySet()) {
                LOG.debug("input = " + entry.getValue());
                String localFileRefs = localiseFileRefs(entry.getValue(), true);
                mapTempInputFileParameters.put( entry.getKey(), localFileRefs.substring(1));
            }

            Map<String, String> mapTempOutputFileParameters = 
                new HashMap<String, String>(mapOutputFileParameters[c]);
            for( Entry<String, String> entry : mapOutputFileParameters[c].entrySet()) {
                LOG.debug("output = " + entry.getValue());
                String localFileRefs = localiseFileRefs(entry.getValue(), false);
                mapTempOutputFileParameters.put( entry.getKey(), localFileRefs.substring(1));
            }

            // feed processor with localized parameters
            lastProcessor.setInputFileParameters(mapTempInputFileParameters);
            lastProcessor.setOutputFileParameters(mapTempOutputFileParameters);

            // chain processor
            if(firstProcessor == null )
                firstProcessor = lastProcessor;
            else {
                Processor help = firstProcessor;  
                while ( help.next() != null ) {
                    help = help.next();
                }
                help.next(lastProcessor);
            }
        }

        // Processors for stdin and stdout
        StreamProcessor streamProcessorIn = createStreamProcessorIn(strStdinFile);
        if( streamProcessorIn != null ) {
            streamProcessorIn.next(firstProcessor);
            firstProcessor = streamProcessorIn;
        } 

        OutputStream oStdout = createStdOut(strStdoutFile);
        StreamProcessor streamProcessorOut = new StreamProcessor(oStdout);
        lastProcessor.next(streamProcessorOut);

        int retVal = firstProcessor.execute();

        String text = convertToResult(oStdout, strStdoutFile);

        if (retVal != 0)
            throw new RuntimeException(text);

        delocalizeOutputParameters(mapOutputFileParameters);

        return text;
    }

    private static String localiseFileRefs(String localFile, boolean copy) throws IOException {
    	LogFactory.getLog(ToolWrapper.class).debug("localiseFileRefs copy: "+copy+" localFile: "+localFile);
        String[] remoteFileRefs = localFile.split(SEP);
        StringBuilder localFileRefs = new StringBuilder();
        String workingDir = workingDir();
        for( int i = 0; i < remoteFileRefs.length; i++ ){
            Filer filer = Filer.create(remoteFileRefs[i]);
            filer.setWorkingDir(workingDir);
            //filer.localize();
            filer.localize_(copy);
            localFileRefs.append(localFileRefs + SEP + filer.getRelativeFileRef());
        }
        return localFileRefs.toString();
    }
    
    private static String convertToResult(OutputStream oStdout, final String strStdoutFile) {
        if( oStdout instanceof ByteArrayOutputStream )
            return  new String( ((ByteArrayOutputStream)oStdout).toByteArray() );
        return strStdoutFile;
    }

    private static OutputStream createStdOut(final String strStdoutFile) throws IOException {
        if( strStdoutFile != null ) 
            return Filer.create(strStdoutFile).getOutputStream();
        // default: output to bytestream
        return new ByteArrayOutputStream();
    }

    private static StreamProcessor createStreamProcessorIn(final String strStdinFile) throws IOException {
        if( strStdinFile != null ) {
            InputStream iStdin = Filer.create(strStdinFile).getInputStream();
            return new StreamProcessor(iStdin);
        }
        return null;
    }

    private static void delocalizeOutputParameters(Map<String, String>[] mapOutputFileParameters) throws IOException {
        for(int i = 0; i < mapOutputFileParameters.length; i++ ) {
            Map<String, String> outputFileParameters = mapOutputFileParameters[i];
            delocalizeOutputParameters(outputFileParameters);
        }
    }

    private static void delocalizeOutputParameters(Map<String, String> outputFileParameters) throws IOException {
        String workingDir = workingDir();
        for( String strFile : outputFileParameters.values())
        {
            String[] localFileRefs = strFile.split(SEP);
            for( int j = 0; j < localFileRefs.length; j++ ){
                Filer filer = Filer.create(localFileRefs[j]);
                filer.setWorkingDir(workingDir);
                filer.delocalize();
            }
        }
    }

    private static String workingDir() {
        return System.getProperty("user.dir");
    }

}
