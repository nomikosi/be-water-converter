package com.converter;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class ConverterToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project,
                                        @NotNull ToolWindow toolWindow) {
        ConverterPanel panel = new ConverterPanel();
        ContentFactory cf = ContentFactory.getInstance();
        Content content = cf.createContent(panel.getContent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
