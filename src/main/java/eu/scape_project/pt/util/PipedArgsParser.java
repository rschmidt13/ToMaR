/*
 * Copyright 2013 ait.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.scape_project.pt.util;

import java.io.IOException;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Parses a commandline for setParameters, values and redirect symbols
 *
 * @author Matthias Rella, DME-AIT
 */
public class PipedArgsParser implements CmdLineParser {

    private static final int BLANK = 32;

    private static Log LOG = LogFactory.getLog(PipedArgsParser.class);

    /**
     * Tokenizer which reads input command line.
     */
    private StreamTokenizer tokenizer;

    /**
     * File name after "&lt" in command line.
     */
    private String strStdinFile = "";

    /**
     * File name after "&gt" in command line.
     */
    private String strStdoutFile = "";
    
    /**
     * Recognized commands.
     */
    private Command[] commands;

    /**
     * Public interface for parsing a command line. The form of the
     * command line should be (--key="value")* (&lt stdinfile)? (&gt stdoutfile)?.
     * Results can be fetched via getters.
     * @param strCmdLine input String
     */
    @Override
    public void parse(String strCmdLine) throws IOException {

        strStdinFile = "";
        strStdoutFile = "";
        tokenizer = new StreamTokenizer(
                new StringReader(strCmdLine));

        Varbox s = S();

        strStdinFile = s.stdin;
        strStdoutFile = s.stdout;
        commands = s.commands.toArray(new Command[0]);
        
    }

    /**
     * Reads next token from Tokenizer and increases column number
     */
    private int nextToken() throws IOException {
        //colnr += tokenizer.sval != null ? tokenizer.sval.length() : 1;
        int token = tokenizer.nextToken();
        LOG.debug("tokenizer.sval = " + tokenizer.sval + ", token = " + token );
        return token;
    }

    /**
     * Gets current token of Tokenizer
     */
    private int currentToken() {
        return tokenizer.ttype;
    }

    /**
     * Gets recognized commands 
     * @return Map mapArguments
     */
    @Override
    public Command[] getCommands() {
        return this.commands;
    }

    /**
     * Gets recognized stdin file name
     * @return String
     */
    @Override
    public String getStdinFile() {
        return this.strStdinFile == "" ? null : this.strStdinFile;
    }

    /**
     * Gets recognized stdout file name
     * @return String
     */
    @Override
    public String getStdoutFile() {
        return this.strStdoutFile == "" ? null : this.strStdoutFile;
    }

    static class Varbox {
        String stdin = "";
        String stdout = "";
        List<Command> commands = new ArrayList<Command>();
    }

    /**
     * Matches (stdin >)? (command (| command)*)? (> stdout)?
     * 
     * Grammar: 
     * S = STDIN > COMMANDS > STDOUT
     * S = STDIN > > STDOUT
     * S = COMMANDS > STDOUT
     * S = STDIN > COMMANDS
     * S = COMMANDS
     * COMMANDS = COMMAND (| COMMAND)*
     * COMMAND = TOOL ACTION PAIR*
     * PAIR = --literal="literal"
     * TOOL = literal
     * ACTION = literal
     * STDIN = literal
     * STDOUT = literal
     * 
     * Transformed with semantic attributes:
     * 
     * S = literal R
     *      synthesized attributes: 
     *      : S.stdin = R.stdin
     *      : S.stdout = R.stdout
     *      : S.commands = R.commands
     *      inherited attributes:
     *      : R.x = literal
     * R = '>' literal R2
     *      synthesized attributes:
     *      : R.stdin = R.x
     *      : R.commands = R2.commands
     *      : R.stdout = R2.stdout
     *      inherited attributes:
     *      : R2.tool = literal
     * R = R2
     *      synthesized attributes:
     *      : R.stdin = R2.stdin
     *      : R.commands = R2.commands
     *      : R.stdout = R2.stdout
     *      inherited attributes: 
     *      : R2.tool = R.x
     * R2 = literal_1 ('-' PAIR)* ('|' COMMAND)* ('>' literal_2)?
     *      synthesized attributes:
     *      : foreach PAIR: _pairs += PAIR.pair
     *      : foreach COMMAND: _commands += COMMAND.command
     *      : R2.commands = [new Command(
     *                          tool=R.tool,
     *                          action=literal_1,
     *                          pairs=_pairs)]
     *                      + _commands
     *      : R2.stdout = literal_2
     *      : R2.stdin = ""
     * PAIR = '-' literal '=' quoted_literal
     *      synthesized attributes:
     *      : PAIR.pair = (literal, quoted_literal)
     * COMMAND = literal_1 literal_2 ('-' PAIR)*
     *      synthesized attributes:
     *      : COMMAND.command = new Command(
     *                              tool=literal_1,
     *                              action=literal_2,
     *                              pairs=PAIRS.pairs )
     *
     */

    private Varbox S() throws IOException {
        if( nextToken() != '"' && currentToken() != StreamTokenizer.TT_WORD )
            throw new IOException("input must start with a literal");
        return R(tokenizer.sval);
    }

    private Varbox R( String x ) throws IOException {
        if( nextToken() == '>' ) {
            if( nextToken() != '"' && currentToken() != StreamTokenizer.TT_WORD ) 
                throw new IOException("a tool name must follow after token >");
            Varbox r = R2(tokenizer.sval);
            r.stdin = x;
            return r;
        }
        tokenizer.pushBack();
        return R2(x);
    }

    private Varbox R2( String tool ) throws IOException {
        if( nextToken() != '"' && currentToken() != StreamTokenizer.TT_WORD) 
            throw new IOException("an action name must follow after tool");
        String action = tokenizer.sval;
        Command c = new Command(tool, action);
        Varbox r = new Varbox();
        nextToken();
        while( currentToken() == '-' ) {
            Entry<String, String> pair = PAIR();
            c.addPair( pair.getKey(), pair.getValue() );
            nextToken();
        }
        r.commands.add(c);
        while( currentToken() == '|' ) {
            r.commands.add(COMMAND());
        }
        if( currentToken() == '>' ) {
            if( nextToken() != '"' && currentToken() != StreamTokenizer.TT_WORD )
                throw new IOException("a stdout literal must follow after token '>'");
            r.stdout = tokenizer.sval;
        }
        return r;

    }

    private Entry<String, String> PAIR() throws IOException {
        String key = null;
        String value = null;

        if (nextToken() != '-')
            throw new IOException("unrecognized token "
                + (tokenizer.ttype >= BLANK ? (char)tokenizer.ttype : ""));

        if (nextToken() != StreamTokenizer.TT_WORD) 
            throw new IOException("unrecognized token, expecting key word");
        key = tokenizer.sval;

        if (nextToken() != '=') 
            throw new IOException("unrecognized token, expecting '='");
        LOG.debug("= found");

        if (nextToken() != '"' && currentToken() != StreamTokenizer.TT_WORD ) 
            throw new IOException("unrecognized token, expecting string literal");
        LOG.debug("\" found");
        LOG.debug("value found");

        value = tokenizer.sval;

        return new SimpleEntry<String, String>(key, value);
    }

    private Command COMMAND() throws IOException {
        if( nextToken() != StreamTokenizer.TT_WORD ) {
            throw new IOException("command must start with tool literal");
        }
        String tool = tokenizer.sval;
        if( nextToken() != StreamTokenizer.TT_WORD ) {
            throw new IOException("second argument of command must be an action literal");
        }
        String action = tokenizer.sval;
        Command c = new Command(tool, action);
        while( nextToken() == '-' ) {
            Entry<String, String> pair = PAIR();
            c.addPair( pair.getKey(), pair.getValue() );
        }
        return c;
    }

}
