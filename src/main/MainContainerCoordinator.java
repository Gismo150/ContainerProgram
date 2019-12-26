package main;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main entry point of the whole program
 *
 * @author Daniel Braun
 */
public class MainContainerCoordinator {

    public static void main(String[] args) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        logger.setLevel(Level.ALL);
        Handler handler = null;
        try {
            handler = new FileHandler(Config.FILEPATH + "/ContainerCoordinator_Log.xml", true);
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.setUseParentHandlers(false);
        logger.info("-----------------------------------");
        logger.info("-----------------------------------");
        logger.config("CONFIGURATION:");
        logger.config("Filepath: " + Config.FILEPATH);
        logger.config("Host path: " + Config.HOSTPATH);
        logger.config("Container path: " + Config.CONTAINERPATH);
        logger.config("repositories.json is read to: " + Config.FILEPATH + "/" + Config.JSONFILENAME);
        logger.config("results.json is output to: " + Config.FILEPATH + "/" + Config.RESULTFILENAME);
        logger.config("Using analysis tool : " + Config.ANALYSISTOOL);

        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        String systemStartTime = formatter.format(calendar.getTime());
        long startTime = System.nanoTime();

        if(args.length != 1){
            System.err.println("Expected 1 argument. Got " + args.length + " argument(s).\n Please provide exactly one positive number (including 0).");
        } else {
            try {
                int arrayIndex = Integer.parseInt(args[0]);
                if(arrayIndex >= 0) {
                    new ContainerCoordinator(logger, startTime, systemStartTime).run(arrayIndex);
                } else {
                    System.err.println("Number must be greater or equals 0.");
                    System.err.println("Aborting.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("Expected a number as an argument.");
                System.err.println(e.getMessage());
                System.err.println("Aborting.");
                System.exit(1);
            }
        }
    }
}
