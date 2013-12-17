package org.yinwang.rubysonar.ast;

import org.jetbrains.annotations.NotNull;
import org.yinwang.rubysonar.State;
import org.yinwang.rubysonar.types.TupleType;
import org.yinwang.rubysonar.types.Type;

import java.util.List;


public class Tuple extends Sequence {

    public Tuple(List<Node> elts, int start, int end) {
        super(elts, start, end);
    }


    @NotNull
    @Override
    public Type transform(State s) {
        TupleType t = new TupleType();
        for (Node e : elts) {
            t.add(transformExpr(e, s));
        }
        return t;
    }


    @NotNull
    @Override
    public String toString() {
        return "<Tuple:" + start + ":" + elts + ">";
    }


    @NotNull
    @Override
    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append("(");

        int idx = 0;
        for (Node n : elts) {
            if (idx != 0) {
                sb.append(", ");
            }
            idx++;
            sb.append(n.toDisplay());
        }

        sb.append(")");
        return sb.toString();
    }


    @Override
    public void visit(@NotNull NodeVisitor v) {
        if (v.visit(this)) {
            visitNodes(elts, v);
        }
    }
}