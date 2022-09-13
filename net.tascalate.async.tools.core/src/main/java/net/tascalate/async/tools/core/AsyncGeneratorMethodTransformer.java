/**
 * ﻿Copyright 2015-2022 Valery Silaev (http://vsilaev.com)
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:

 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.

 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.tascalate.async.tools.core;

import static net.tascalate.async.tools.core.BytecodeIntrospection.createOuterClassMethodArgFieldName;
import static net.tascalate.async.tools.core.BytecodeIntrospection.invisibleTypeAnnotationsOf;
import static net.tascalate.async.tools.core.BytecodeIntrospection.isLoadOpcode;
import static net.tascalate.async.tools.core.BytecodeIntrospection.methodsOf;
import static net.tascalate.async.tools.core.BytecodeIntrospection.visibleTypeAnnotationsOf;
import static net.tascalate.asmx.Opcodes.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.tascalate.asmx.Handle;
import net.tascalate.asmx.MethodVisitor;
import net.tascalate.asmx.Opcodes;
import net.tascalate.asmx.Type;
import net.tascalate.asmx.tree.AbstractInsnNode;
import net.tascalate.asmx.tree.ClassNode;
import net.tascalate.asmx.tree.FieldInsnNode;
import net.tascalate.asmx.tree.FieldNode;
import net.tascalate.asmx.tree.IincInsnNode;
import net.tascalate.asmx.tree.InsnList;
import net.tascalate.asmx.tree.InsnNode;
import net.tascalate.asmx.tree.IntInsnNode;
import net.tascalate.asmx.tree.InvokeDynamicInsnNode;
import net.tascalate.asmx.tree.JumpInsnNode;
import net.tascalate.asmx.tree.LabelNode;
import net.tascalate.asmx.tree.MethodInsnNode;
import net.tascalate.asmx.tree.MethodNode;
import net.tascalate.asmx.tree.TryCatchBlockNode;
import net.tascalate.asmx.tree.VarInsnNode;

class AsyncGeneratorMethodTransformer extends AbstractAsyncMethodTransformer {
    private final static Type ASYNC_GENERATOR_METHOD_TYPE = Type.getObjectType("net/tascalate/async/core/AsyncGeneratorMethod");
    private final static Type LAZY_GENERATOR_TYPE         = Type.getObjectType("net/tascalate/async/core/LazyGenerator");
    
    AsyncGeneratorMethodTransformer(ClassNode               classNode,
                                    MethodNode              originalAsyncMethodNode,
                                    Map<String, MethodNode> accessMethods,
                                    Helper                  helper) {
        super(classNode, originalAsyncMethodNode, accessMethods, helper);
    }

    @Override
    protected ClassNode transform() {
        return transform(ASYNC_GENERATOR_METHOD_TYPE);
    }
    
    @Override
    protected MethodVisitor createReplacementAsyncMethod(String asyncTaskClassName) {
        return createReplacementAsyncMethod(asyncTaskClassName, ASYNC_GENERATOR_METHOD_TYPE, "generator", LAZY_GENERATOR_TYPE);
    }
   
    @Override
    protected MethodVisitor addAnonymousClassRunMethod(ClassNode asyncRunnableClass, FieldNode outerClassField) {
        List<MethodNode> ownerMethods = methodsOf(classNode);
        
        boolean isStatic = (originalAsyncMethod.access & Opcodes.ACC_STATIC) != 0;
        int thisShiftNecessary = isStatic ? 1 : 0;

        Type[] originalArgTypes = Type.getArgumentTypes(originalAsyncMethod.desc);
        if (log.isTraceEnabled()) {
            log.trace("Method has " + originalArgTypes.length + " arguments");
        }

        MethodNode result = (MethodNode)asyncRunnableClass.visitMethod(
            ACC_PROTECTED, "doRun", "()V", null, new String[]{"java/lang/Throwable"}
        );

        result.visitAnnotation(SUSPENDABLE_ANNOTATION_TYPE.getDescriptor(), true);

        LabelNode methodStart = new LabelNode();
        LabelNode methodEnd = new LabelNode();

        Map<LabelNode, LabelNode> labelsMap = new IdentityHashMap<>();
        for (AbstractInsnNode l = originalAsyncMethod.instructions.getFirst(); l != null; l = l.getNext()) {
            if (!(l instanceof LabelNode))
                continue;
            labelsMap.put((LabelNode) l, new LabelNode());
        }

        List<TryCatchBlockNode> tryCatchBlocks = new ArrayList<>();
        // Try-catch blocks
        for (Iterator<?> it = originalAsyncMethod.tryCatchBlocks.iterator(); it.hasNext();) {
            TryCatchBlockNode tn = (TryCatchBlockNode) it.next();
            TryCatchBlockNode newTn = new TryCatchBlockNode(
                labelsMap.get(tn.start), labelsMap.get(tn.end),
                labelsMap.get(tn.handler), tn.type);
            newTn.invisibleTypeAnnotations = copyTypeAnnotations(invisibleTypeAnnotationsOf(tn));
            newTn.visibleTypeAnnotations = copyTypeAnnotations(visibleTypeAnnotationsOf(tn));
            tryCatchBlocks.add(newTn);
        }
        result.tryCatchBlocks = tryCatchBlocks;

        InsnList newInstructions = new InsnList();
        newInstructions.add(methodStart);

        // Instructions
        int argumentsLength = Arrays.stream(originalArgTypes).mapToInt(a -> a.getSize()).sum();
        for (AbstractInsnNode insn = originalAsyncMethod.instructions.getFirst(); null != insn; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode vin = (VarInsnNode) insn;
                // "this" -> outer class "this"
                if (!isStatic && vin.getOpcode() == ALOAD && vin.var == 0) {
                    if (log.isTraceEnabled()) {
                        log.trace("Found " + BytecodeTraceUtil.toString(vin));
                    }

                    newInstructions.add(new VarInsnNode(ALOAD, 0));
                    newInstructions.add(new FieldInsnNode(GETFIELD, asyncRunnableClass.name, outerClassField.name, outerClassField.desc));
                    continue;
                }

                // original method had arguments
                if (vin.getOpcode() != RET && (vin.var > 0 || isStatic)) {
                    if (log.isTraceEnabled()) {
                        log.trace("Found " + BytecodeTraceUtil.toString(vin));
                    }
                    // method argument -> inner class field
                    int argIdx = findOriginalArgumentIndex(originalArgTypes, vin.var, isStatic);
                    Type argType = argIdx < 0 ? null : originalArgTypes[argIdx];
                    if (null != argType) {
                        String argName = createOuterClassMethodArgFieldName(argIdx);
                        String argDesc = argType.getDescriptor();

                        newInstructions.add(new VarInsnNode(ALOAD, 0));
                        if (isLoadOpcode(vin.getOpcode())) {
                            assert (argType.getOpcode(ILOAD) == vin.getOpcode()) : 
                                "Wrong opcode " + vin.getOpcode() + ", expected " + argType.getOpcode(ILOAD);

                            newInstructions.add(new FieldInsnNode(GETFIELD, asyncRunnableClass.name, argName, argDesc));
                        } else {
                            assert (argType.getOpcode(ISTORE) == vin.getOpcode()) : 
                                "Wrong opcode " + vin.getOpcode() + ", expected " + argType.getOpcode(ISTORE);

                            if (argType.getSize() == 2) {
                                newInstructions.add(new InsnNode(DUP_X2));
                                newInstructions.add(new InsnNode(POP));
                            } else {
                                newInstructions.add(new InsnNode(SWAP));
                            }
                            newInstructions.add(new FieldInsnNode(PUTFIELD, asyncRunnableClass.name, argName, argDesc));
                        }
                        continue;
                    } else {
                        // decrease local variable indexes
                        newInstructions.add(new VarInsnNode(vin.getOpcode(), vin.var - argumentsLength + thisShiftNecessary));
                        continue;
                    }
                }
            } else if (insn instanceof IincInsnNode) {
                IincInsnNode iins = (IincInsnNode)insn; 
                int argIdx = findOriginalArgumentIndex(originalArgTypes, iins.var, isStatic);
                Type argType = argIdx < 0 ? null : originalArgTypes[argIdx];
                if (null != argType) {
                    String argName = createOuterClassMethodArgFieldName(argIdx);
                    String argDesc = argType.getDescriptor();
                    // i+=2 ==> this.val$2 = this.val$2 + 2;
                    newInstructions.add(new VarInsnNode(ALOAD, 0));
                    newInstructions.add(new FieldInsnNode(GETFIELD, asyncRunnableClass.name, argName, argDesc));
                    newInstructions.add(new IntInsnNode(SIPUSH, iins.incr));
                    newInstructions.add(new InsnNode(IADD));
                    newInstructions.add(new VarInsnNode(ALOAD, 0));
                    newInstructions.add(new InsnNode(SWAP));
                    newInstructions.add(new FieldInsnNode(PUTFIELD, asyncRunnableClass.name, argName, argDesc));                    
                } else {
                    newInstructions.add(new IincInsnNode(iins.var - argumentsLength + thisShiftNecessary, iins.incr));
                }
                continue;
                
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                MethodNode accessMethod;
                if ((fin.getOpcode() == GETSTATIC || fin.getOpcode() == GETFIELD) && 
                    (accessMethod = getAccessMethod(fin.owner, fin.name, fin.desc, "G")) != null) {
                    
                    newInstructions.add(
                        new MethodInsnNode(INVOKESTATIC, classNode.name, accessMethod.name, accessMethod.desc, false)
                    );
                    continue;
                }
                if ((fin.getOpcode() == PUTSTATIC || fin.getOpcode() == PUTFIELD) && 
                    (accessMethod = getAccessMethod(fin.owner, fin.name, fin.desc, "S")) != null) {
                    
                    newInstructions.add(
                        new MethodInsnNode(INVOKESTATIC, classNode.name, accessMethod.name, accessMethod.desc, false)
                    );
                    continue;
                }
            } else if (insn instanceof MethodInsnNode) {
                // instance method call -> outer class instance method call
                // using a generated access method
                MethodInsnNode min = (MethodInsnNode) insn;
                MethodNode accessMethod;

                if ((min.getOpcode() == INVOKEVIRTUAL || 
                     min.getOpcode() == INVOKESPECIAL || 
                     min.getOpcode() == INVOKESTATIC
                    ) && 
                    (accessMethod = getAccessMethod(min.owner, min.name, min.desc, "M")) != null) {
                    
                    if (log.isTraceEnabled()) {
                        log.trace("Found " + BytecodeTraceUtil.toString(min));
                    }
                    
                    newInstructions.add(
                        new MethodInsnNode(INVOKESTATIC, classNode.name, accessMethod.name, accessMethod.desc, (classNode.access & ACC_INTERFACE) != 0)
                    );
                    continue;

                } else if (min.getOpcode() == INVOKESTATIC && CALL_CONTXT_NAME.equals(min.owner)) {
                    switch (min.name) {
                        case "yield":
                            Type[] args = Type.getArgumentTypes(min.desc);
                            newInstructions.add(new VarInsnNode(ALOAD, 0));
                            if (null != args) {
                                switch (args.length) {
                                    case 0: 
                                        break;
                                    case 1: 
                                        newInstructions.add(new InsnNode(SWAP));
                                        break;
                                    default:
                                        throw new IllegalStateException("Can't support YIELD method with more than one argument");
                                }
                            }
                            newInstructions.add(
                                new MethodInsnNode(INVOKEVIRTUAL, 
                                                   ASYNC_GENERATOR_METHOD_TYPE.getInternalName(), 
                                                   "yield", 
                                                   Type.getMethodDescriptor(Type.getReturnType(min.desc), args), 
                                                   false
                                )
                            );
                            continue;
                        case "interrupted":
                            newInstructions.add(new VarInsnNode(ALOAD, 0));
                            newInstructions.add(
                                    new MethodInsnNode(INVOKEVIRTUAL, 
                                                       ASYNC_GENERATOR_METHOD_TYPE.getInternalName(), 
                                                       "interrupted", 
                                                       Type.getMethodDescriptor(Type.BOOLEAN_TYPE), 
                                                       false
                                    )
                            );                            
                            continue;
                        case "await":
                            newInstructions.add(
                                new MethodInsnNode(INVOKESTATIC, 
                                                   ASYNC_METHOD_EXECUTOR_TYPE.getInternalName(), 
                                                   "await", 
                                                   Type.getMethodDescriptor(OBJECT_TYPE, COMPLETION_STAGE_TYPE), 
                                                   false
                                )
                            );
                            continue;
                        case "throwing":
                            int exceptionTypesCount = Type.getArgumentTypes(min.desc).length;
                            // POP-out method arguments from stack
                            for (int i = exceptionTypesCount; i > 0; i--) {
                                newInstructions.add(new InsnNode(POP));
                            }
                            continue;                            
                        case "async":
                            throw new IllegalStateException("Async result must be used only inside non-generator methods");
                    }
                }
            } else if (insn instanceof InvokeDynamicInsnNode) {
                Object[] opts = findOwnerInvokeDynamic(insn, ownerMethods);
                if (null != opts) {
                    Handle h = (Handle)opts[0];
                    MethodNode lambdaAccess = getAccessMethod(h.getOwner(), h.getName(), h.getDesc(), "L");
                    newInstructions.add(
                        new MethodInsnNode(INVOKESTATIC, classNode.name, lambdaAccess.name, lambdaAccess.desc, (classNode.access & ACC_INTERFACE) != 0)
                    );
                    continue;
                }
            } else if (insn.getOpcode() == ARETURN) {
                // GOTO methodEnd instead of returning value
                newInstructions.add(new JumpInsnNode(GOTO, methodEnd));
                continue;
            } else if (insn instanceof LabelNode) {
                newInstructions.add(labelsMap.get(insn));
                continue;
            }

            // do not make changes
            newInstructions.add(insn.clone(labelsMap));
        }
        newInstructions.add(methodEnd);

        // POP value from stack that was placed before ARETURN
        newInstructions.add(new InsnNode(POP));
        newInstructions.add(new InsnNode(RETURN));

        result.instructions = newInstructions;
        // Maxs
        // 2 for exception handling & asyncResult replacement
        result.maxLocals = Math.max(originalAsyncMethod.maxLocals - argumentsLength + thisShiftNecessary, 2);
        result.maxStack = Math.max(originalAsyncMethod.maxStack, 2);

        return result;        
    }
}
