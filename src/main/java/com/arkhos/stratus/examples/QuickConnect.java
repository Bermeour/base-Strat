package com.arkhos.stratus.examples;

import com.arkhos.stratus.config.StratusConfig;
import com.arkhos.stratus.session.StratusSession;
import com.arkhos.stratus.ui.ScreenViewer;

public class QuickConnect {

    public static void main(String[] args) throws Exception {

        StratusConfig config = StratusConfig.builder("xxxxx", 23)
                .build();

        ScreenViewer viewer = new ScreenViewer("Stratus VOS");
        viewer.show();

        try (StratusSession s = new StratusSession(config)) {
            s.addListener(viewer);
            s.connect()
             .waitForUpdate(15_000)
             .sendText("login\r")
             .waitForText("Username:", 5_000)
             .sendText("xxxxx\r")
             .waitForText("Password:", 5_000)
             .sendText("xxxxx\r")
             .waitForUpdate(10_000);

            System.out.println(s.getScreen().getText());

            Thread.sleep(5000); // mantener la ventana abierta
        }
    }
}
