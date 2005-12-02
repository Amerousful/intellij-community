package com.intellij.uiDesigner.propertyInspector.editors;

import com.intellij.uiDesigner.propertyInspector.PropertyEditor;
import com.intellij.uiDesigner.propertyInspector.properties.IntroFontProperty;
import com.intellij.uiDesigner.RadComponent;
import com.intellij.uiDesigner.lw.FontDescriptor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.project.Project;

import javax.swing.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

/**
 * @author yole
 */
public class FontEditor extends PropertyEditor {
  private TextFieldWithBrowseButton myTextField = new TextFieldWithBrowseButton();
  private FontDescriptor myValue;
  private Project myProject;
  private String myPropertyName;

  public FontEditor(String propertyName) {
    myPropertyName = propertyName;
    myTextField.getTextField().setBorder(null);
    myTextField.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        FontEditorDialog dlg = new FontEditorDialog(myProject, myPropertyName);
        dlg.setValue(myValue);
        dlg.show();
        if (dlg.getExitCode() == DialogWrapper.OK_EXIT_CODE) {
          myValue = dlg.getValue();
          myTextField.setText(IntroFontProperty.descriptorToString(myValue));
        }
      }
    });
  }

  public Object getValue() throws Exception {
    return myValue;
  }

  public JComponent getComponent(RadComponent component, Object value, boolean inplace) {
    myProject = component.getModule().getProject();
    myValue = (FontDescriptor) value;
    myTextField.setText(IntroFontProperty.descriptorToString(myValue));
    return myTextField;
  }

  public void updateUI() {
    SwingUtilities.updateComponentTreeUI(myTextField);
  }
}
