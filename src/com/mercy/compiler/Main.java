package com.mercy.compiler;

import com.mercy.compiler.BackEnd.*;
import com.mercy.compiler.Entity.Entity;
import com.mercy.compiler.Entity.FunctionEntity;
import com.mercy.compiler.Entity.VariableEntity;
import com.mercy.compiler.FrontEnd.AST;
import com.mercy.compiler.FrontEnd.ASTBuilder;
import com.mercy.compiler.FrontEnd.ParserErrorListener;
import com.mercy.compiler.INS.Instruction;
import com.mercy.compiler.Parser.MalicLexer;
import com.mercy.compiler.Parser.MalicParser;
import com.mercy.compiler.Type.Type;
import com.mercy.compiler.Utility.InternalError;
import com.mercy.compiler.Utility.LibFunction;
import com.mercy.compiler.Utility.SemanticError;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.LinkedList;
import java.util.List;

import static com.mercy.compiler.Type.Type.*;
import static com.mercy.compiler.Utility.LibFunction.LIB_PREFIX;
import static java.lang.System.*;

public class Main {
    public static void main(String[] args) throws Exception {
        parseOption(args);

        InputStream is = new FileInputStream(Option.inFile);
        PrintStream os = new PrintStream(new FileOutputStream(Option.outFile));

        try {
            compile(is, os);
        } catch (SemanticError error) {
            err.println(error.getMessage());
            exit(1);
        } catch (InternalError error) {
            err.println(error.getMessage());
            exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            exit(1);
        }
    }

    public static void compile(InputStream sourceCode, PrintStream asmCode) throws Exception {
        ANTLRInputStream input = new ANTLRInputStream(sourceCode);
        MalicLexer lexer = new MalicLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MalicParser parser = new MalicParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ParserErrorListener());
        ParseTree tree = parser.compilationUnit();

        ParseTreeWalker walker = new ParseTreeWalker();
        ASTBuilder listener = new ASTBuilder();

        walker.walk(listener, tree);   // 0th pass, CST -> AST

        AST ast  = listener.getAST();
        ast.loadLibrary(getLibrary()); // load library function
        Type.initializeBuiltinType();

        ast.resolveSymbol();                          // 1st pass, extract info of class and function
        ast.checkType();                              // 2nd pass, check type
        if (Option.enableOutputIrrelevantElimination) // 3rd pass, eliminate output-irrelevant code
            ast.eliminateOutputIrrelevantNode();

        // disable global allocation when the number of entities is too large (to satisfy the time and memory limit)
        boolean backupSettingForTest = Option.enableGlobalRegisterAllocation;
        if (ast.scope().allLocalVariables().size() > 256)
            Option.enableGlobalRegisterAllocation = false;

        // generate IR
        IRBuilder irBuilder = new IRBuilder(ast);
        irBuilder.generateIR();

        // emit instructions
        InstructionEmitter emitter = new InstructionEmitter(irBuilder);
        emitter.emit();

        // build control flow graph
        ControlFlowAnalyzer cfgBuilder = new ControlFlowAnalyzer(emitter);
        cfgBuilder.buildControlFlow();

        // optimize data flow
        DataFlowAnalyzer dataFlowAnalyzer = new DataFlowAnalyzer(emitter);
        dataFlowAnalyzer.optimize();

        if (Option.printInstruction)        // for debug
            printInstructions(emitter.functionEntities());

        // allocate register
        RegisterConfig registerConfig = new RegisterConfig();
        if (Option.enableGlobalRegisterAllocation) {
            Allocator allocator = new Allocator(emitter, registerConfig);
            allocator.allocate();
        } else {
            NaiveAllocator allocator = new NaiveAllocator(emitter, registerConfig);
            allocator.allocate();
        }

        // translate to x86 nasm
        Translator translator = new Translator(emitter, registerConfig);
        List<String> asm = translator.translate();
        for (String s : asm)
            asmCode.println(s);

        Option.enableGlobalRegisterAllocation = backupSettingForTest;
    }

    private static List<Entity> getLibrary() {
        List<Entity> ret = new LinkedList<>();

        // lib function
        ret.add(new LibFunction(voidType, "print", "printf", new Type[]{stringType}).getEntity());
        ret.add(new LibFunction(voidType, "println", "puts", new Type[]{stringType}).getEntity());
        ret.add(new LibFunction(stringType, "getString", null).getEntity());
        ret.add(new LibFunction(integerType, "getInt", null).getEntity());
        ret.add(new LibFunction(stringType, "toString", new Type[]{integerType}).getEntity());
        ret.add(new LibFunction(integerType, LIB_PREFIX + "printInt", LIB_PREFIX + "printInt", new Type[]{integerType}).getEntity());
        ret.add(new LibFunction(integerType, LIB_PREFIX + "printlnInt", LIB_PREFIX + "printlnInt", new Type[]{integerType}).getEntity());
        ret.add(new LibFunction(integerType, LIB_PREFIX + "malloc", "malloc", new Type[]{integerType}).getEntity());
        // null
        ret.add(new VariableEntity(null, nullType, "null", null));

        return ret;
    }

    private static void printInstructions(List<FunctionEntity> functionEntities) {
        for (FunctionEntity entity : functionEntities) {
            err.println("==== " + entity.name()  + " ====");
            if (entity.bbs() == null)
                continue;
            for (BasicBlock basicBlock : entity.bbs()) {
                for (Instruction ins : basicBlock.ins()) {
                    err.println(ins.toString());
                }
            }
        }
    }

    private static void printUsage() {
        out.println("Usage: java -jar Malic.jar [options]");
        out.println("Options:");
        out.println("  -in   <file> : M* language source code");
        out.println("  -out  <file> : NASM output");
        out.println("  -help        : print this help page");
        exit(0);
    }

    private static void parseOption(String []args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--print-ins":
                    Option.printInstruction = true;
                    break;
                case "--print-remove":
                    Option.printRemoveInfo = true;
                    break;
                case "-in":
                    if (i + 1 >= args.length)
                        err.println("invalid argument for input file, use default setting instead");
                    else
                        Option.inFile = args[++i];
                    break;
                case "-out":
                    if (i + 1 >= args.length)
                        err.println("invalid argument for output file, use default setting instead");
                    else
                        Option.outFile = args[++i];
                    break;
                case "-help":
                    printUsage();
            }
        }

        if (Option.inFile == null || Option.outFile == null) {
            printUsage();
        }
    }
}
