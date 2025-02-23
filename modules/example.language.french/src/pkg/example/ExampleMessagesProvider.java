package pkg.example;

import module java.base;
import module teambattle.api;

public class ExampleMessagesProvider extends AbstractResourceBundleProvider implements MessageResourcesProvider {

    public ExampleMessagesProvider() {
        super("java.properties");
    }

    protected String toBundleName(String baseName, Locale locale) {
        return "pkg." + locale.getLanguage() + "." + "additional";
    }

    public ResourceBundle getBundle(String baseName, Locale locale) {
        if (locale.equals(Locale.FRENCH)) {
            return super.getBundle(baseName, locale);
        }
        return null;
    }
}
