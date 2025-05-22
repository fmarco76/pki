// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2014 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---

package com.netscape.cmstools.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.netscape.certsrv.util.JSONSerializer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.dogtagpki.cli.CLI;
import org.dogtagpki.cli.CLIModule;
import org.dogtagpki.cli.CommandCLI;

/**
 * @author Endi S. Dewata
 */
public class HelpCLI extends CommandCLI {

    MainCLI mainCLI;

    public HelpCLI(MainCLI parent) {
        super("help", "Show help messages", parent);
        mainCLI = parent;
    }

    @Override
    public String getFullName() {
        return name;
    }
@Override
    public void createOptions() {
        options.addOption("i", "interactive", true, "AI assisted help");

    }

    @Override
    public void execute(CommandLine cmd) throws Exception {

        if (cmd.hasOption('i')) {
            startInteractiveAISession(cmd.getOptionValue('i'));
            return;
        }
        String[] cmdArgs = cmd.getArgs();

        String manPage = null;
        if (cmdArgs.length == 0) {
            // no command specified, show the pki man page
            manPage = parent.getManPage();

        } else {
            // find all modules handling the specified command
            List<CLIModule> modules = parent.findModules(cmdArgs[0]);

            // find the module that has a man page starting from the last one
            for (int i = modules.size() - 1; i >= 0; i--) {
                CLIModule module = modules.get(i);
                CLI cli = module.getCLI();
                manPage = cli.getManPage();
                if (manPage != null) break;
            }

            // if no module has a man page, show the pki man page
            if (manPage == null)
                manPage = parent.getManPage();
        }

        while (true) {
            // display man page for the command
            ProcessBuilder pb = new ProcessBuilder(
                    "/usr/bin/man",
                    manPage);

            pb.inheritIO();
            Process p = pb.start();
            int rc = p.waitFor();

            if (rc == 16) {
                // man page not found, find the parent command
                int i = manPage.lastIndexOf('-');
                if (i >= 0) {
                    // parent command exists, try again
                    manPage = manPage.substring(0, i);
                    continue;

                }
                // parent command not found, stop
                break;

            }
            // man page found or there's a different error, stop
            break;
        }
    }

    private void startInteractiveAISession(String endpoint) throws Exception {
        System.out.println("Welcome to the interactive help...");
        String request;
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        HttpClient httpClient = HttpClients.createDefault();
        Completition c = new Completition();
        c.setPrompt("DogtagPKI CLI usage");
        do {
            String response = processRequest(httpClient,endpoint,c);
            System.out.println(response);
            System.out.println();
            System.out.print("> ");
            request = r.readLine();
            c.setPrompt(request);
        } while (!request.equals("exit"));
    }
    
    
    private String processRequest(HttpClient httpClient, String endPoint, Completition c) throws Exception {
        StringEntity requestEntity = new StringEntity(c.toJSON(), ContentType.APPLICATION_JSON);
        HttpPost post = new HttpPost("http://" + endPoint + "/completion");
        post.setEntity(requestEntity);
        
        HttpResponse rs = httpClient.execute(post);
        
        HttpEntity responseEntity = rs.getEntity();
        
        String result = EntityUtils.toString(responseEntity);
        
        
        CompletitionResponse cr = JSONSerializer.fromJSON(result, CompletitionResponse.class);

        return cr.getContent();
    }

    public static class Completition implements JSONSerializer {
        String prompt;
        int n_predict = 108;
        int n_keep = -1;

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public int getN_predict() {
            return n_predict;
        }

        public void setN_predict(int n_predict) {
            this.n_predict = n_predict;
        }

        public int getN_keep() {
            return n_keep;
        }

        public void setN_keep(int n_keep) {
            this.n_keep = n_keep;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown=true)
    public static class CompletitionResponse implements JSONSerializer {
        String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
        
    }
}
