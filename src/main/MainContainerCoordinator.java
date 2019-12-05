package main;
/**
 * Main entry point of the whole program
 *
 * @author Daniel Braun
 */
public class MainContainerCoordinator {

    public static void main(String[] args) {

        if(args.length != 1){
            System.err.println("Expected 1 argument. Got " + args.length + " argument(s).\n Please provide exactly one positive number (including 0).");
        } else {
            try {
                int arrayIndex = Integer.parseInt(args[0]);
                if(arrayIndex >= 0) {
                    new ContainerCoordinator().run(arrayIndex);
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
