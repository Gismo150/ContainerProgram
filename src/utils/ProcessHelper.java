package utils;

import main.Program;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ProcessHelper {

    public static int executeProcess(ProcessBuilder processBuilder, Program program) {
        try {
            Process process = processBuilder.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                if(line.contains("Downloaded recipe")) {
                    program.getConanDependencies().add(line.substring(0, line.indexOf(":")));
                }
            }

            BufferedReader reader2 = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));

            String line2;
            while ((line2 = reader2.readLine()) != null) {
                program.getErrorMessages().add(line2);
            }

            return process.waitFor();

        } catch (IOException e) {
            System.err.println("Internal process IOException error");
            System.err.println(e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            System.err.println("Internal process InterruptedException error");
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
