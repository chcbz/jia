package cn.jia.core.errcode;

import cn.jia.core.annotation.ErrorCodeModule;

import javax.annotation.processing.*;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 错误码注解处理器
 * 
 * 编译时收集所有 @ErrorCodeModule 标注的类，生成 ErrCodeRegistry 类
 * 实现零反射的错误码注册
 * 
 * @author chenzg
 */
@SupportedAnnotationTypes("cn.jia.core.annotation.ErrorCodeModule")
public class ErrCodeProcessor extends AbstractProcessor {

    private static final String REGISTRY_CLASS_NAME = "ErrCodeRegistry";
    private static final String PACKAGE_NAME = "cn.jia.core.errcode";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        List<ErrorCodeInfo> errorCodes = new ArrayList<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(ErrorCodeModule.class)) {
            if (element instanceof TypeElement typeElement) {
                processErrorCodeClass(typeElement, errorCodes);
            }
        }

        if (!errorCodes.isEmpty()) {
            generateRegistryClass(errorCodes);
        }

        return true;
    }

    private void processErrorCodeClass(TypeElement typeElement, List<ErrorCodeInfo> errorCodes) {
        List<VariableElement> staticFields = new ArrayList<>();

        // 获取所有静态字段
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) enclosed;
                if (field.getModifiers().contains(Modifier.STATIC)) {
                    staticFields.add(field);
                }
            }
        }

        // 提取错误码信息
        for (VariableElement field : staticFields) {
            TypeMirror fieldType = field.asType();
            
            // 检查字段类型是否是 cn.jia.core.exception.EsErrorConstants
            if (isAssignableFrom(fieldType, "cn.jia.core.exception.EsErrorConstants")) {
                String code = getFieldValue(field, "code");
                String message = getFieldValue(field, "message");
                
                if (code != null && message != null) {
                    errorCodes.add(new ErrorCodeInfo(code, message));
                }
            }
        }
    }

    private boolean isAssignableFrom(TypeMirror type, String targetType) {
        return type.toString().equals(targetType);
    }

    private String getFieldValue(VariableElement field, String fieldName) {
        for (Element enclosed : field.getEnclosingElement().getEnclosedElements()) {
            if (enclosed instanceof VariableElement ve && 
                ve.getSimpleName().toString().equals(fieldName)) {
                Object constantValue = ve.getConstantValue();
                return constantValue != null ? constantValue.toString() : null;
            }
        }
        return null;
    }

    private void generateRegistryClass(List<ErrorCodeInfo> errorCodes) {
        try {
            Messager messager = processingEnv.getMessager();
            messager.printMessage(Diagnostic.Kind.NOTE, 
                "Generating ErrCodeRegistry with " + errorCodes.size() + " error codes");

            JavaFileObject fileObject = processingEnv.getFiler()
                .createSourceFile(PACKAGE_NAME + "." + REGISTRY_CLASS_NAME);

            try (PrintWriter out = new PrintWriter(fileObject.openWriter())) {
                out.println("package " + PACKAGE_NAME + ";");
                out.println();
                out.println("/**");
                out.println(" * 错误码注册中心 - 编译时自动生成");
                out.println(" * ");
                out.println(" * 由 ErrCodeProcessor 自动生成，请勿手动修改");
                out.println(" */");
                out.println("public final class " + REGISTRY_CLASS_NAME + " {");
                out.println();
                out.println("    private " + REGISTRY_CLASS_NAME + "() {}");
                out.println();
                out.println("    /**");
                out.println("     * 注册所有错误码");
                out.println("     */");
                out.println("    public static void registerAll() {");

                for (ErrorCodeInfo info : errorCodes) {
                    out.println("        ErrCodeHolder.register(\"" + info.code + "\", \"" + escapeString(info.message) + "\");");
                }

                out.println("    }");
                out.println("}");
            }

            messager.printMessage(Diagnostic.Kind.NOTE, 
                "ErrCodeRegistry generated successfully");

        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Failed to generate ErrCodeRegistry: " + e.getMessage());
        }
    }

    private String escapeString(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    /**
         * 错误码信息
         */
        private record ErrorCodeInfo(String code, String message) {
    }
}
