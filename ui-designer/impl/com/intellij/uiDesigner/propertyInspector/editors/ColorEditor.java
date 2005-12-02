package com.intellij.uiDesigner.propertyInspector.editors;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.uiDesigner.RadComponent;
import com.intellij.uiDesigner.UIDesignerBundle;
import com.intellij.uiDesigner.lw.ColorDescriptor;
import com.intellij.uiDesigner.propertyInspector.PropertyEditor;
import com.intellij.uiDesigner.propertyInspector.renderers.ColorRenderer;

import javax.swing.*;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.plaf.ColorChooserUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.List;

/**
 * @author yole
 */
public class ColorEditor extends PropertyEditor {
  private String myPropertyName;
  private TextFieldWithBrowseButton myTextField = new TextFieldWithBrowseButton();
  private ColorDescriptor myValue;
  private Project myProject;

  public ColorEditor(String propertyName) {
    myPropertyName = propertyName;
    myTextField.getTextField().setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
    myTextField.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        MyColorChooserDialog dialog = new MyColorChooserDialog(myProject);
        dialog.setSelectedValue(myValue);
        dialog.show();
        if (dialog.getExitCode() == DialogWrapper.OK_EXIT_CODE) {
          myValue = dialog.getSelectedValue();
          updateTextField();
        }
      }
    });
  }

  public Object getValue() throws Exception {
    return myValue;
  }

  public JComponent getComponent(RadComponent component, Object value, boolean inplace) {
    myValue = (ColorDescriptor) value;
    myProject = component.getModule().getProject();
    updateTextField();
    return myTextField;
  }

  private void updateTextField() {
    myTextField.setText(myValue.toString());
  }

  public void updateUI() {
    SwingUtilities.updateComponentTreeUI(myTextField);
  }

  private class MyColorChooserDialog extends DialogWrapper {
    private JColorChooser myColorChooser;
    private MyDescriptorChooserPanel mySwingChooserPanel;
    private MyDescriptorChooserPanel mySystemChooserPanel;
    private MyDescriptorChooserPanel myAWTChooserPanel;

    public MyColorChooserDialog(Project project) {
      super(project, false);
      setTitle(UIDesignerBundle.message("color.chooser.title", myPropertyName));
      init();
    }

    protected JComponent createCenterPanel() {
      myColorChooser = new JColorChooser();
      mySwingChooserPanel = new MyDescriptorChooserPanel(UIDesignerBundle.message("color.chooser.swing.palette"), collectSwingColorDescriptors());
      myColorChooser.addChooserPanel(mySwingChooserPanel);
      mySystemChooserPanel = new MyDescriptorChooserPanel(UIDesignerBundle.message("color.chooser.system.palette"),
                                                          collectColorFields(SystemColor.class, true));
      myColorChooser.addChooserPanel(mySystemChooserPanel);
      myAWTChooserPanel = new MyDescriptorChooserPanel(UIDesignerBundle.message("color.chooser.awt.palette"),
                                                       collectColorFields(Color.class, false));
      myColorChooser.addChooserPanel(myAWTChooserPanel);
      return myColorChooser;
    }

    private void selectTabForColor(final ColorDescriptor value) {
      String tabName;

      if (value.getSwingColor() != null) {
        tabName = mySwingChooserPanel.getDisplayName();
      }
      else if (value.getSystemColor() != null) {
        tabName = mySystemChooserPanel.getDisplayName();
      }
      else if (value.getAWTColor() != null) {
        tabName = myAWTChooserPanel.getDisplayName();
      }
      else {
        return;
      }

      final ColorChooserUI ui = myColorChooser.getUI();
      try {
        //noinspection HardCodedStringLiteral
        final Field field = ui.getClass().getDeclaredField("tabbedPane");
        field.setAccessible(true);
        JTabbedPane tabbedPane = (JTabbedPane) field.get(ui);
        for(int i=0; i<tabbedPane.getTabCount(); i++) {
          if (tabbedPane.getTitleAt(i).equals(tabName)) {
            tabbedPane.setSelectedIndex(i);
            break;
          }
        }
      }
      catch (NoSuchFieldException e) {
        // ignore
      }
      catch (IllegalAccessException e) {
        // ignore
      }
    }

    private List<ColorDescriptor> collectSwingColorDescriptors() {
      ArrayList<ColorDescriptor> result = new ArrayList<ColorDescriptor>();
      UIDefaults defaults = UIManager.getDefaults();
      Enumeration e = defaults.keys ();
      while(e.hasMoreElements()) {
        Object key = e.nextElement();
        Object value = defaults.get(key);
        if (key instanceof String && value instanceof Color) {
          result.add(ColorDescriptor.fromSwingColor((String) key));
        }
      }
      return result;
    }

    private List<ColorDescriptor> collectColorFields(final Class aClass, final boolean isSystem) {
      ArrayList<ColorDescriptor> result = new ArrayList<ColorDescriptor>();
      Field[] colorFields = aClass.getDeclaredFields();
      for(Field field: colorFields) {
        if ((field.getModifiers() & Modifier.STATIC) != 0 &&
            Color.class.isAssignableFrom(field.getType()) &&
            Character.isLowerCase(field.getName().charAt(0))) {
          final ColorDescriptor color = isSystem
                                        ? ColorDescriptor.fromSystemColor(field.getName())
                                        : ColorDescriptor.fromAWTColor(field.getName());
          result.add(color);
        }
      }
      return result;
    }

    public void setSelectedValue(final ColorDescriptor value) {
      myColorChooser.setColor(value);
      selectTabForColor(value);
    }

    public ColorDescriptor getSelectedValue() {
      final Color color = myColorChooser.getColor();
      if (color instanceof ColorDescriptor) {
        return (ColorDescriptor) color;
      }
      return new ColorDescriptor(color);
    }
  }

  private static class MyDescriptorChooserPanel extends AbstractColorChooserPanel {
    private String myDisplayName;
    private ColorDescriptor[] myColorDescriptors;
    private JList myDescriptorList;

    public MyDescriptorChooserPanel(final String displayName, List<ColorDescriptor> colorDescriptorList) {
      myDisplayName = displayName;

      Collections.sort(colorDescriptorList, new Comparator<ColorDescriptor>() {
        public int compare(final ColorDescriptor o1, final ColorDescriptor o2) {
          return o1.toString().compareTo(o2.toString());
        }
      });

      myColorDescriptors = colorDescriptorList.toArray(new ColorDescriptor[colorDescriptorList.size()]);
    }

    public void updateChooser() {
      myDescriptorList.setSelectedValue(getColorFromModel(), true);
    }

    protected void buildChooser() {
      setLayout(new BorderLayout());
      myDescriptorList = new JList(myColorDescriptors);
      myDescriptorList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      myDescriptorList.setVisibleRowCount(15);
      myDescriptorList.setCellRenderer(new ColorRenderer());
      myDescriptorList.addListSelectionListener(new ListSelectionListener() {
        public void valueChanged(ListSelectionEvent e) {
          ColorDescriptor descriptor = (ColorDescriptor) myDescriptorList.getSelectedValue();
          getColorSelectionModel().setSelectedColor(descriptor);
        }
      });
      add(new JScrollPane(myDescriptorList), BorderLayout.CENTER);
    }

    public String getDisplayName() {
      return myDisplayName;
    }

    public Icon getSmallDisplayIcon() {
      return null;
    }

    public Icon getLargeDisplayIcon() {
      return null;
    }
  }
}
