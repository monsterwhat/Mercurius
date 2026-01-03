package Utils;

/**
 * Thread-safe utility to store current user context for async operations
 */
public class AsyncUserContext {
    private static final ThreadLocal<String> currentUser = new ThreadLocal<>();
    
    public static void setCurrentUser(String user) {
        currentUser.set(user);
    }
    
    public static String getCurrentUser() {
        return currentUser.get();
    }
    
    public static void clear() {
        currentUser.remove();
    }
}