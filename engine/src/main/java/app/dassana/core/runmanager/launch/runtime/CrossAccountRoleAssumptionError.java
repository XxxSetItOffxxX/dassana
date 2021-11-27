package app.dassana.core.runmanager.launch.runtime;

public class CrossAccountRoleAssumptionError extends RuntimeException{

  public CrossAccountRoleAssumptionError(String message) {
    super(message);
  }

  public CrossAccountRoleAssumptionError(String message, Throwable cause) {
    super(message, cause);
  }
}
