plugins {
    id("org.openjfx.javafxplugin")
}

repositories {
    mavenCentral()
}

javafx {
    version = "25"
    modules("javafx.controls", "javafx.graphics", "javafx.fxml")
}
