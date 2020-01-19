package main;

import Models.RMetaData;
import utils.FileHelper;
import utils.JsonReader;
import utils.JsonWriter;
import utils.ProcessHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The ContainerCoordinator class that implements all required build steps for an automatic repository evaluation.
 * @author Daniel Braun
 */
public class ContainerCoordinator {
    private ProcessBuilder processBuilder;
    private ArrayList<String> errorMessages;
    private ArrayList<String> conanDependencies;
    private String localBuildStatus = "UNKNOWN";
    private Logger logger;
    private long startTime;
    private String systemStartTime;

    public ContainerCoordinator(Logger logger, long startTime, String systemStartTime) {
        this.logger = logger;
        this.startTime = startTime;
        this.systemStartTime = systemStartTime;
        processBuilder = new ProcessBuilder();
        errorMessages = new ArrayList<>();
        conanDependencies = new ArrayList<>();
    }

    /**
     * Main entry function that must be called on construction to start the build pipeline.
     * @param arrayIndex The index to the repository within the repositories.json file.
     */
    public void run(int arrayIndex) {
        JsonReader.getInstance().checkArgInRange(arrayIndex);


        RMetaData rMetaData = JsonReader.getInstance().deserializeRepositoryFromJsonArray(arrayIndex);

        logger.info("-----------------------------------");
        logger.info("Running container pipeline at index: "+ arrayIndex + " for repository with id/owner/name: " + rMetaData.getId() + "/" + rMetaData.getOwner() + "/" + rMetaData.getName());

        cloneRepository(rMetaData);
        compile(rMetaData);

        if(localBuildStatus.equals("SUCCESS"))
            runAnalysis(gatherBuildTargetsAndExtractLLVMIR(rMetaData));

        rMetaData.setPackageDependencies(conanDependencies);
        rMetaData.setErrorMessage(errorMessages);
        updateMetaData(rMetaData, arrayIndex);

        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        long endTime   = System.nanoTime();
        long duration = endTime - startTime;

        //logger.info("-----------------------------------");
        logger.info("ContainerCoordinator started at: " + systemStartTime);
        logger.info("ContainerCoordinator terminated at: " + formatter.format(calendar.getTime()));
        logger.info("Overall execution time in seconds: " + TimeUnit.NANOSECONDS.toSeconds(duration));
        logger.info("Overall execution time in minutes: " + (double)TimeUnit.NANOSECONDS.toSeconds(duration)/60);
        logger.info("Overall execution time in hours: " + (double)TimeUnit.NANOSECONDS.toSeconds(duration)/3600);
        logger.info("ContainerCoordinator finished. Shutting down");


        System.out.println("PRINTING ERRORMESSAGES");
        for(String s: errorMessages)
            System.err.println(s);
        System.out.println("----------------------------------------------------");
        System.out.println("PRINTING CONAN DEPENDENCIES");
        for(String s: conanDependencies)
            System.out.println(s);
        System.out.println("----------------------------------------------------");
    }

    /**
     * Cloning, init submodules and reset working tree.
     * @param rMetaData The Metadata read from the JSON-file.
     */
    private void cloneRepository(RMetaData rMetaData) {
        long startTimeCloning   = System.nanoTime();
        processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH + " && git clone " + rMetaData.getCloneUrl() + " 2>&1");
        int exitVal = ProcessHelper.executeProcess(processBuilder, this);
        if (exitVal == 0) {
            System.out.println("Cloning finished");
            logger.info("FINISHED: CLONING");
        } else {
            System.err.println("Failed to clone the repository!");
            errorMessages.add("Failed to clone the repository!");
            logger.severe("FAILED: CLONING");
        }
        long endTimeCloning  = System.nanoTime();
        long durationCloning = endTimeCloning - startTimeCloning;
        logger.info("Cloning took " + TimeUnit.NANOSECONDS.toSeconds(durationCloning) + " seconds - Repository size: " + rMetaData.getSize());

        long startTimeSubmodules   = System.nanoTime();
        processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH + "/" + rMetaData.getName() + " && git submodule update --init --recursive 2>&1");
        int exitVal1 = ProcessHelper.executeProcess(processBuilder, this);
        if (exitVal1 == 0) {
            System.out.println("Cloning submodules finished");
            logger.info("FINISHED: CLONING SUBMODULES");
        } else {
            System.err.println("Failed to clone the repository submodules!");
            errorMessages.add("Failed to clone the repository submodules!");
            logger.severe("FAILED: CLONING SUBMODULES");
        }
        long endTimeSubmodules  = System.nanoTime();
        long durationSubmodules = endTimeSubmodules - startTimeSubmodules;
        logger.info("Cloning submodules took " + TimeUnit.NANOSECONDS.toSeconds(durationSubmodules) + " seconds");

        long startTimeReset   = System.nanoTime();
        processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH + "/" +rMetaData.getName()+" && git reset --hard " + rMetaData.getLatestCommitId());
        int exitVal2 = ProcessHelper.executeProcess(processBuilder, this);
        if (exitVal2== 0) {
            System.out.println("Reset current working tree to commit id: " +rMetaData.getLatestCommitId());
            logger.info("FINISHED: RESET WORKING TREE");
        } else {
            System.err.println("Failed to reset the current working tree to commit id: "+rMetaData.getLatestCommitId());
            errorMessages.add("Failed to reset the current working tree to commit id: "+rMetaData.getLatestCommitId());
            logger.severe("FAILED: RESET WORKING TREE");
        }
        long endTimeReset = System.nanoTime();
        long durationReset = endTimeReset - startTimeReset;
        logger.info("Resetting working tree took " + TimeUnit.NANOSECONDS.toSeconds(durationReset) + " seconds");

        System.out.println("----------------------------------------------------");
    }

    /**
     * Install dependencies, prepare build folders, generate build files and compile.
     * @param rMetaData The metadata read from the json file.
     */
    private void compile(RMetaData rMetaData) {

        System.out.println("RUNNING: CONAN INSTALL");
        //Delete existing build folder, making sure we are building everything from scratch.
        long startTimeConan   = System.nanoTime();
        processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH + "/" + rMetaData.getName() + " && rm -f -r build && mkdir build && cd build && yes y | conan install .. -pr=clang --build=missing");
        int exitVal1 = ProcessHelper.executeProcess(processBuilder, this);
        if (exitVal1 == 0) {
            System.out.println("FINISHED: CONAN INSTALL");
            logger.info("FINISHED: CONAN INSTALL");
        } else {
            System.err.println("FAILED: CONAN INSTALL");
            rMetaData.setBuildStatus("FAILED");
            errorMessages.add("FAILED: CONAN INSTALL");
            logger.severe("FAILED: CONAN INSTALL");
        }
        long endTimeConan = System.nanoTime();
        long durationConan = endTimeConan - startTimeConan;
        logger.info("Installing dependencies took " + TimeUnit.NANOSECONDS.toSeconds(durationConan) + " seconds");

        System.out.println("----------------------------------------------------");

        long startTimeFolderPrep   = System.nanoTime();
        if (exitVal1 == 0) {
            System.out.println("RUNNING: FOLDER PREPARATION");
            processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH +  "/" + rMetaData.getName() + " && mkdir buildDest && cd buildDest && mkdir exe && mkdir lib && mkdir ar");
            int exitVal2 = ProcessHelper.executeProcess(processBuilder, this);
            if (exitVal2 == 0) {
                System.out.println("FINISHED: FOLDER PREPARATION");
                logger.info("FINISHED: FOLDER PREPARATION");
            } else {
                System.err.println("FAILED: FOLDER PREPARATION");
                rMetaData.setBuildStatus("FAILED");
                errorMessages.add("FAILED: FOLDER PREPARATION");
                logger.severe("FAILED: FOLDER PREPARATION");
            }
            long endTimeFolderPrep = System.nanoTime();
            long durationFolderPrep = endTimeFolderPrep - startTimeFolderPrep;
            logger.info("Build folder preparation took " + TimeUnit.NANOSECONDS.toSeconds(durationFolderPrep) + " seconds");

            System.out.println("----------------------------------------------------");

            System.out.println("RUNNING: CMAKE PREPARATION");

            long startTimeCMakePrep   = System.nanoTime();
            processBuilder.command("bash", "-c", "export LLVM_COMPILER=clang && export CC=wllvm && export CXX=wllvm++ " +
                    "&& cd " + Config.CONTAINERPATH + "/" + rMetaData.getName() + "/build " +
                    "&& cmake -G \"Unix Makefiles\" -DCMAKE_BUILD_TYPE=Release" +
                    " -DCMAKE_RUNTIME_OUTPUT_DIRECTORY=" + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/exe " +
                    "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=" + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/lib " +
                    "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=" + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/ar ..");
            int exitVal3 = ProcessHelper.executeProcess(processBuilder, this);
            if (exitVal3 == 0) {
                System.out.println("FINISHED: CMAKE PREPARATION");
                logger.info("FINISHED: CMAKE PREPARATION");
            } else {
                System.err.println("FAILED: CMAKE PREPARATION");
                rMetaData.setBuildStatus("FAILED");
                errorMessages.add("FAILED: CMAKE PREPARATION");
                logger.severe("FAILED: CMAKE PREPARATION");
            }
            long endTimeCMakePrep = System.nanoTime();
            long durationCMakePrep = endTimeCMakePrep - startTimeCMakePrep;
            logger.info("CMake preparation took " + TimeUnit.NANOSECONDS.toSeconds(durationCMakePrep) + " seconds");

            System.out.println("----------------------------------------------------");
            if(exitVal3 == 0) {
                System.out.println("RUNNING: CMAKE BUILD");

                long startTimeCMakeBuild = System.nanoTime();
                //NOTE: the environment variables must be set again for each subprocess. Any environment variable set is "lost" again when the subprocess exits!
                processBuilder.command("bash", "-c", "export LLVM_COMPILER=clang && export CC=wllvm && export CXX=wllvm++ && cd " + Config.CONTAINERPATH + "/" + rMetaData.getName() + "/build && cmake --build .");
                int exitVal4 = ProcessHelper.executeProcess(processBuilder, this);
                if (exitVal4 == 0) {
                    System.out.println("FINISHED: CMAKE BUILD");
                    logger.info("FINISHED: CMAKE BUILD");
                    rMetaData.setBuildStatus("SUCCESS");
                    localBuildStatus = "SUCCESS";
                } else {
                    System.err.println("FAILED: CMAKE BUILD");
                    rMetaData.setBuildStatus("FAILED");
                    errorMessages.add("FAILED: CMAKE BUILD");
                    logger.severe("FAILED: CMAKE BUILD");
                }
                long endTimeCMakeBuild = System.nanoTime();
                long durationCMakeBuild = endTimeCMakeBuild - startTimeCMakeBuild;
                logger.info("CMake build took " + TimeUnit.NANOSECONDS.toSeconds(durationCMakeBuild) + " seconds");

                System.out.println("----------------------------------------------------");
            }
        }
    }

    /**
     * Collect the build targets (from buildDest folder).
     * Run the WLLVM tool extract bc and disassemble to LLVM IR.
     * @param rMetaData The metadata read from the json file
     * @return ArrayList of file paths to the LLVM IR files.
     */
    private ArrayList<String> gatherBuildTargetsAndExtractLLVMIR(RMetaData rMetaData) {

        long startTimeExtractLLVMIR = System.nanoTime();
        ArrayList<String> llFilePathList = new ArrayList<>();
        List<String> lsExe;
        List<String> lsLib;
        List<String> lsAr;

        //Sometimes build files get still written into the users specified paths that were defined in the CMakeLists.txt, instead of the path set at runtime by us.
        //This will result in a build success but no build files will be found!
        System.out.println("GATHERING BUILD TARGETS");
        lsExe = FileHelper.getAllFileNamesOfDir(Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/exe");
        lsLib = FileHelper.getAllFileNamesOfDir(Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/lib");
        lsAr = FileHelper.getAllFileNamesOfDir(Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/ar");
        System.out.println("Build summary:\n" + lsExe.size() + " Executables\n" + lsLib.size() + " Libraries\n" + lsAr.size() + " Archives");
        logger.info("Build summary: " + lsExe.size() + " Executables " + lsLib.size() + " Libraries " + lsAr.size() + " Archives");
        System.out.println("----------------------------------------------------");

        rMetaData.setExecutables(lsExe.size());
        rMetaData.setLibraries(lsLib.size());
        rMetaData.setArchives(lsAr.size());
        for(String s : lsExe) {
            if(extractBC("EXE", s, rMetaData) == 0)
                disassambleToll(llFilePathList, s);
        }
        for(String s : lsLib) {
            if(extractBC("LIB", s, rMetaData) == 0)
                disassambleToll(llFilePathList, s);
        }
        for(String s : lsAr) {
            if(extractBC("AR", s, rMetaData) == 0)
                disassambleToll(llFilePathList, s);
        }

        System.out.println("LISTING ALL GENERATED .ll FILES");
        for(String ll : llFilePathList) {
            System.out.println(ll);
        }
        System.out.println("----------------------------------------------------");
        long endTimeExtractLLVMIR = System.nanoTime();
        long durationExtractLLVMIR = endTimeExtractLLVMIR - startTimeExtractLLVMIR;
        logger.info("Extracting and disassambling all build targets into LLVM IR took " + TimeUnit.NANOSECONDS.toSeconds(durationExtractLLVMIR) + " seconds");

        return llFilePathList;
    }

    /**
     * Extract the LLVM bitcode files from the build targets.
     * @param target The build target, either exe, lib or ar.
     * @param fileName The path and filename to the build target file.
     * @param rMetaData The metadata read from the json file.
     * @return Returns an int indicating the exit code.
     */
    private int extractBC(String target, String fileName, RMetaData rMetaData) {
        String succMsg = "";
        String errMsg = "";
        int exitVal = 1;
        switch(target) {
            case "EXE":
                System.out.println("EXTRACTING LLVM BITCODE (*.bc) FILE FROM EXECUTABLE: "+ fileName);
                processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/exe/ && extract-bc --linker llvm-link-8 " + fileName);
                exitVal = ProcessHelper.executeProcess(processBuilder, this);
                succMsg = "Writing output to: " + fileName + ".bc";
                errMsg  = "FAILED: EXTRACT LLVM BITCODE";
                break;
            case "LIB":
                System.out.println("EXTRACTING LLVM BITCODE (*.bc) FILE FROM LIBRARY: "+ fileName);
                processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/lib/ && extract-bc --linker llvm-link-8 " + fileName);
                exitVal = ProcessHelper.executeProcess(processBuilder, this);
                succMsg = "Writing output to: " + fileName + ".bc";
                errMsg  = "FAILED: EXTRACT LLVM BITCODE";
                break;
            case "AR":
                System.out.println("EXTRACTING LLVM BITCODE MODULE (*a.bc) FILE FROM ARCHIVE: "+ fileName);
                processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/ar/ && extract-bc -b --archiver llvm-ar-8 --linker llvm-link-8 " + fileName + " 2>&1");
                exitVal = ProcessHelper.executeProcess(processBuilder, this);
                succMsg = "Writing output to: " + fileName + ".bc";
                errMsg  = "FAILED: EXTRACT LLVM BITCODE";
                break;
            default:
                System.err.println("ERROR: Unknown build target.");
                break;
        }

        if (exitVal == 0) {
            //The extract-bc command on archives writes output to stderr. Hence, we redirect the output onto stdout (which also keeps the stored error messages clean.
            //However, the if-condition below is required to prevent double printing.
            if(!target.equals("AR"))
                System.out.println(succMsg);
        } else {
            System.err.println(errMsg);
        }
        System.out.println("----------------------------------------------------");
        return exitVal;
    }

    /**
     * Disassembling all LLVM bitcode files into LLVM IR.
     * @param llFileList ArrayList that stores the paths to the generated LLVM IR files.
     * @param fileName Path to the LLVM bitcode that gets disassembled.
     * @return Returns an int indicating the exit code of the llvm-dis tool.
     */
    private int disassambleToll(ArrayList<String> llFileList, String fileName){
        String pathTollFile = "";
        int exitVal = 1;
        System.out.println("DISASSEMBLING " + fileName + ".bc FILE INTO LLVM IR (*.ll)");
        processBuilder.command("bash", "-c", "llvm-dis-8 " + fileName + ".bc");
        exitVal = ProcessHelper.executeProcess(processBuilder, this);
        pathTollFile = fileName + ".ll";

        if (exitVal == 0) {
            System.out.println("Writing output to: " + fileName + ".ll");
            llFileList.add(pathTollFile);
        } else {
            System.err.println("FAILED: LLVM IR DISASSEMBLE");
        }
        System.out.println("----------------------------------------------------");
        return exitVal;
    }

    /**
     * Running the specified analysis tool on the list of LLVM IR files.
     * @param llFileList List of paths to the LLVM IR files.
     */
    private void runAnalysis(ArrayList<String> llFileList) {
        long startTimeAnalysis = System.nanoTime();
        System.out.println("RUNNING ANALYSIS");
        for(String llFile: llFileList) {
            processBuilder.command("bash", "-c", "./"+ Config.ANALYSISTOOL + " ./.."+ llFile + " ./.." + Config.CONTAINERPATH);
            int exitVal = ProcessHelper.executeProcess(processBuilder, this);
            if (exitVal == 0) {
                System.out.println("ANALYSIS SUCCESS FOR: " + llFile);
            } else {
                System.err.println("ANALYSIS FAILED FOR: " + llFile);
            }
        }
        long endTimeAnalysis = System.nanoTime();
        long durationAnalysis = endTimeAnalysis - startTimeAnalysis;
        logger.info("Analysis of all LLVM IR files took " + TimeUnit.NANOSECONDS.toSeconds(durationAnalysis) + " seconds");

    }


    /**
     * Update the metadata at a specific index.
     * @param rMetaData The updated metadata.
     * @param arrayIndex The index within the repositories.json file.
     */
    private void updateMetaData(RMetaData rMetaData, int arrayIndex){
        JsonWriter.getInstance().updateRepositoryInJsonArray(rMetaData, arrayIndex);
    }


    public ArrayList<String> getErrorMessages() {
        return errorMessages;
    }

    public void setErrorMessages(ArrayList<String> errorMessages) {
        this.errorMessages = errorMessages;
    }
    public ArrayList<String> getConanDependencies() {
        return conanDependencies;
    }

    public void setConanDependencies(ArrayList<String> conanDependencies) {
        this.conanDependencies = conanDependencies;
    }
}
