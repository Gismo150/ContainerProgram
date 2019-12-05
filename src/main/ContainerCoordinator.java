package main;

import Models.RMetaData;
import utils.FileHelper;
import utils.JsonReader;
import utils.JsonWriter;
import utils.ProcessHelper;
import java.util.ArrayList;
import java.util.List;

public class ContainerCoordinator {
    private ProcessBuilder processBuilder;
    private ArrayList<String> errorMessages;
    private ArrayList<String> conanDependencies;
    private String localBuildStatus = "UNKNOWN";

    public ContainerCoordinator() {
        processBuilder = new ProcessBuilder();
        errorMessages = new ArrayList<>();
        conanDependencies = new ArrayList<>();
    }

    public void run(int arrayIndex) {
        JsonReader.getInstance().checkArgInRange(arrayIndex);

        RMetaData rMetaData = JsonReader.getInstance().deserializeRepositoryFromJsonArray(arrayIndex); //TODO is this called serialize?

        cloneRepository(rMetaData);
        compile(rMetaData);

        if(localBuildStatus.equals("SUCCESS"))
            runAnalysis(gatherBuildTargetsAndExtractLLVMIR(rMetaData));

        cleanSharedDir(rMetaData);
        rMetaData.setPackageDependencies(conanDependencies);
        rMetaData.setErrorMessage(errorMessages);
        updateMetaData(rMetaData, arrayIndex);

        System.out.println("PRINTING ERRORMESSAGES");
        for(String s: errorMessages)
            System.err.println(s);
        System.out.println("----------------------------------------------------");
        System.out.println("PRINTING CONAN DEPENDENCIES");
        for(String s: conanDependencies)
            System.out.println(s);
        System.out.println("----------------------------------------------------");
    }

    private void cloneRepository(RMetaData rMetaData) {
        processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH + " && git clone " + rMetaData.getCloneUrl() + " 2>&1");
        int exitVal = ProcessHelper.executeProcess(processBuilder, this);
        if (exitVal == 0) {
            System.out.println("Cloning finished");
        } else {
            System.err.println("Failed to clone the repository!");
            errorMessages.add("Failed to clone the repository!");
        }


        processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH + "/" +rMetaData.getName()+" && git reset --hard " + rMetaData.getLatestCommitId());
        int exitVal1 = ProcessHelper.executeProcess(processBuilder, this);
        if (exitVal1== 0) {
            System.out.println("Reset current working tree to commit id: " +rMetaData.getLatestCommitId());
        } else {
            System.err.println("Failed to reset the current working tree to commit id: "+rMetaData.getLatestCommitId());
            errorMessages.add("Failed to reset the current working tree to commit id: "+rMetaData.getLatestCommitId());
        }
        System.out.println("----------------------------------------------------");
    }

    private void compile(RMetaData rMetaData) {

    System.out.println("RUNNING: CONAN INSTALL");                                                       //Delete existing build folder, making sure we are building everything from scratch.
        processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH + "/" + rMetaData.getName() + " && rm -f -r build && mkdir build && cd build && yes y | conan install .. -pr=clang --build=missing");
        int exitVal1 = ProcessHelper.executeProcess(processBuilder, this);
        if (exitVal1 == 0) {
            System.out.println("FINISHED: CONAN INSTALL");
        } else {
            System.err.println("FAILED: CONAN INSTALL");
            rMetaData.setBuildStatus("FAILED");
            errorMessages.add("FAILED: CONAN INSTALL");
        }
        System.out.println("----------------------------------------------------");

        if (exitVal1 == 0) {
            System.out.println("RUNNING: FOLDER PREPARATION");
            processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH +  "/" + rMetaData.getName() + " && mkdir buildDest && cd buildDest && mkdir exe && mkdir lib && mkdir ar");
            int exitVal2 = ProcessHelper.executeProcess(processBuilder, this);
            if (exitVal2 == 0) {
                System.out.println("FINISHED: FOLDER PREPARATION");
            } else {
                System.err.println("FAILED: FOLDER PREPARATION");
                rMetaData.setBuildStatus("FAILED");
                errorMessages.add("FAILED: FOLDER PREPARATION");
            }
            System.out.println("----------------------------------------------------");

            System.out.println("RUNNING: CMAKE PREPARATION"); // But of course it makes sense to compile your source code into LLVM IR using the production flags in order to ensure that PhASAR analyzes the code "as your machine sees it".
            processBuilder.command("bash", "-c", "export LLVM_COMPILER=clang && export CC=wllvm && export CXX=wllvm++ " +
                    "&& cd " + Config.CONTAINERPATH + "/" + rMetaData.getName() + "/build " +
                    "&& cmake -G \"Unix Makefiles\" -DCMAKE_BUILD_TYPE=Release" +
                    " -DCMAKE_RUNTIME_OUTPUT_DIRECTORY=" + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/exe " +
                    "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=" + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/lib " +
                    "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=" + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/ar ..");
            //TODO: remove these commands within the CmakeLists.txt file! otherwise these are not overwritten by the cmd at runtime
            int exitVal3 = ProcessHelper.executeProcess(processBuilder, this);
            if (exitVal3 == 0) {
                System.out.println("FINISHED: CMAKE PREPARATION");
            } else {
                System.err.println("FAILED: CMAKE PREPARATION");
                rMetaData.setBuildStatus("FAILED");
                errorMessages.add("FAILED: CMAKE PREPARATION");
            }
            System.out.println("----------------------------------------------------");
            if(exitVal3 == 0) {
                System.out.println("RUNNING: CMAKE BUILD");
                processBuilder.command("bash", "-c", "export LLVM_COMPILER=clang && export CC=wllvm && export CXX=wllvm++ && cd " + Config.CONTAINERPATH + "/" + rMetaData.getName() + "/build && cmake --build .");
                int exitVal4 = ProcessHelper.executeProcess(processBuilder, this);
                if (exitVal4 == 0) {
                    System.out.println("FINISHED: CMAKE BUILD");
                    rMetaData.setBuildStatus("SUCCESS");
                    localBuildStatus = "SUCCESS";
                } else {
                    System.err.println("FAILED: CMAKE BUILD");
                    rMetaData.setBuildStatus("FAILED");
                    errorMessages.add("FAILED: CMAKE BUILD");
                }
                System.out.println("----------------------------------------------------");
            }
        }
    }

    private ArrayList<String> gatherBuildTargetsAndExtractLLVMIR(RMetaData rMetaData) {
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

        return llFilePathList;
    }

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
                errMsg  = "Failed to extract LLVM BITCODE!";
                break;
            case "LIB":
                System.out.println("EXTRACTING LLVM BITCODE (*.bc) FILE FROM LIBRARY: "+ fileName);
                processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/lib/ && extract-bc --linker llvm-link-8 " + fileName);
                exitVal = ProcessHelper.executeProcess(processBuilder, this);
                succMsg = "Writing output to: " + fileName + ".bc";
                errMsg  = "Failed to extract LLVM BITCODE!";
                break;
            case "AR":
                System.out.println("EXTRACTING LLVM BITCODE MODULE (*a.bc) FILE FROM ARCHIVE: "+ fileName);
                processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH +  "/" + rMetaData.getName() + "/buildDest/ar/ && extract-bc -b --archiver llvm-ar-8 --linker llvm-link-8 " + fileName + " 2>&1");
                exitVal = ProcessHelper.executeProcess(processBuilder, this);
                succMsg = "Writing output to: " + fileName + ".bc";
                errMsg  = "Failed to extract LLVM BITCODE!";
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
            System.err.println("Failed to extract LLVM IR!");
        }
        System.out.println("----------------------------------------------------");
        return exitVal;
    }

    private void runAnalysis(ArrayList<String> llFileList) {
        System.out.println("RUNNING ANALYSIS");
        for(String llFile: llFileList) {
            processBuilder.command("bash", "-c", "umask 0000 && ./"+ Config.ANALYSISTOOL + " ./.."+ llFile + " ./.." + Config.CONTAINERPATH);
            int exitVal = ProcessHelper.executeProcess(processBuilder, this);
            if (exitVal == 0) {
                System.out.println("ANALYSIS SUCCESS FOR: " + llFile);
            } else {
                System.err.println("ANALYSIS FAILED FOR: " + llFile);
            }
        }
    }

    private void cleanSharedDir(RMetaData rMetaData) {
        System.out.println("----------------------------------------------------");
        processBuilder.command("bash", "-c", "cd " + Config.CONTAINERPATH + " && rm -f -r ./" + rMetaData.getName());

        int exitVal1 = ProcessHelper.executeProcess(processBuilder, this);
        if (exitVal1 == 0) {
            System.out.println("Repository has been deleted!");
        } else {
            System.err.println("Failed to delete repository!");
        }
        System.out.println("----------------------------------------------------");
    }

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
