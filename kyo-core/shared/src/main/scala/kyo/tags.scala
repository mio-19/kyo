package kyo

import scala.annotation.tailrec
import scala.quoted.*

case class Tag[T](tpe: String) extends AnyVal

object Tag:

    import internal.*

    inline given apply[T >: Nothing]: Tag[T] = ${ tagImpl[T] }

    extension [T](t1: Tag[T])

        def show = t1.tpe.takeWhile(_ != ';')

        def <:<[U](t2: Tag[U]): Boolean =
            t1.tpe == t2.tpe || isSubtype(t1, t2)

        def =:=[U](t2: Tag[U]): Boolean =
            t1.tpe == t2.tpe

        def =!=[U](t2: Tag[U]): Boolean =
            t1.tpe != t2.tpe

        def >:>[U](t2: Tag[U]): Boolean =
            t1.tpe == t2.tpe || isSubtype(t2, t1)

    end extension

    private[Tag] object internal:

        // Example Tag
        //
        // class Super
        // class Param1 extends Super
        // class Param2 extends Super
        // class Test[-T, +U]
        //
        // Tag[Test[Param1, Param2]]
        //
        // Test;Super;java.lang.Object;scala.Matchable;scala.Any;[-Param1;Super;java.lang.Object;scala.Matchable;scala.Any;,+Param2;Super;java.lang.Object;scala.Matchable;scala.Any;]
        // |--------------------Test segment---------------------|-|-------------------Param1 segment----------------------|+|-------------------Param2 segment----------------------|
        //                                                     variance                                                  variance

        def isSubtype(subTag: Tag[?], superTag: Tag[?]): Boolean =
            checkType(subTag.tpe, superTag.tpe, 0, 0)

        def checkType(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            checkSegment(subTag, superTag, subIdx, superIdx) && checkParams(subTag, superTag, subIdx, superIdx)

        def checkSegment(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            @tailrec def loop(subIdx: Int, superIdx: Int, superStart: Int): Boolean =
                if subIdx >= subTag.length() || superIdx >= superTag.length() then false
                else
                    val subChar   = subTag.charAt(subIdx)
                    val superChar = superTag.charAt(superIdx)
                    (subChar == ';' && superChar == ';') || {
                        if subChar == superChar then
                            loop(subIdx + 1, superIdx + 1, superStart)
                        else
                            nextSegmentEntry(subTag, subIdx) match
                                case -1  => false
                                case idx => loop(idx, superStart, superStart)
                        end if
                    }
                end if
            end loop
            loop(subIdx, superIdx, superIdx)
        end checkSegment

        def nextSegmentEntry(tag: String, idx: Int): Int =
            @tailrec def loop(idx: Int): Int =
                if idx >= tag.length() then -1
                else
                    tag.charAt(idx) match
                        case ';'             => idx + 1
                        case '[' | ',' | ']' => -1
                        case _               => loop(idx + 1)
            loop(idx)
        end nextSegmentEntry

        def sameType(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            @tailrec def loop(subIdx: Int, superIdx: Int): Boolean =
                val subChar   = subTag.charAt(subIdx)
                val superChar = superTag.charAt(superIdx)
                subChar == superChar && (subChar == ';' || loop(subIdx + 1, superIdx + 1))
            end loop
            loop(subIdx, superIdx) && checkParams(subTag, superTag, subIdx, superIdx)
        end sameType

        def checkParams(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            val subStart   = subTag.indexOf('[', subIdx)
            val superStart = subTag.indexOf('[', superIdx)
            if subStart == -1 || superStart == -1 then
                superStart == superStart
            else
                @tailrec def loop(subIdx: Int, superIdx: Int): Boolean =
                    val variance = subTag.charAt(subIdx)
                    variance == superTag.charAt(superIdx) && {
                        variance match
                            case '=' =>
                                sameType(subTag, superTag, subIdx + 1, superIdx + 1)
                            case '+' =>
                                checkType(subTag, superTag, subIdx + 1, superIdx + 1)
                            case '-' =>
                                checkType(superTag, subTag, superIdx + 1, subIdx + 1)
                        end match
                    } && {
                        val nextSubParam   = nextParam(subTag, subIdx + 1)
                        val nextSuperParam = nextParam(superTag, superIdx + 1)
                        if nextSubParam == -1 || nextSuperParam == -1 then
                            nextSubParam == nextSuperParam
                        else
                            loop(nextSubParam, nextSuperParam)
                        end if
                    }
                end loop
                loop(subStart + 1, superStart + 1)
            end if
        end checkParams

        def nextParam(tag: String, idx: Int): Int =
            @tailrec def loop(opens: Int, idx: Int): Int =
                tag.charAt(idx) match
                    case ',' if opens == 0 =>
                        idx + 1
                    case ']' if opens == 0 =>
                        -1
                    case '[' => loop(opens + 1, idx + 1)
                    case ']' => loop(opens - 1, idx + 1)
                    case _   => loop(opens, idx + 1)
            loop(0, idx)
        end nextParam

        // Macro methods

        def tagImpl[T: Type](using Quotes): Expr[Tag[T]] =
            import quotes.reflect.*

            val encoded = encodeType(TypeRepr.of[T])
            '{ new Tag($encoded) }
        end tagImpl

        def encodeType(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[String] =
            import quotes.reflect.*

            tpe.dealias match
                case tpe if tpe.typeSymbol.isClassDef =>
                    val sym  = tpe.typeSymbol
                    val path = tpe.dealias.baseClasses.map(_.fullName).mkString(";") + ";"
                    val variances = sym.typeMembers.flatMap { v =>
                        if !v.isTypeParam then None
                        else if v.flags.is(Flags.Contravariant) then Some("-")
                        else if v.flags.is(Flags.Covariant) then Some("+")
                        else Some("=")
                    }
                    val params = tpe.typeArgs.map(encodeType)
                    if params.isEmpty then
                        Expr(path)
                    else
                        concat(
                            Expr(s"$path[") ::
                                (variances.zip(params).map((v, p) => Expr(v) :: p :: Nil)
                                    .reduce((a, b) =>
                                        a ::: Expr(",") :: b
                                    ) :+ Expr("]"))
                        )
                    end if
                case AndType(_, _) | OrType(_, _) =>
                    report.errorAndAbort(s"Unsupported Tag type ${tpe.show}")
                case _ =>
                    tpe.asType match
                        case '[tpe] =>
                            Expr.summon[Tag[tpe]] match
                                case None =>
                                    report.errorAndAbort(
                                        s"Please provide an implicit ${TypeRepr.of[Tag[tpe]].show}"
                                    )
                                case Some(value) =>
                                    '{ $value.tpe }
                        case _ =>
                            report.errorAndAbort(s"Unsupported Tag type ${tpe.show}")
            end match
        end encodeType

        def concat(l: List[Expr[String]])(using Quotes): Expr[String] =
            def loop(l: List[Expr[String]], acc: String, exprs: List[Expr[String]]): Expr[String] =
                l match
                    case Nil =>
                        (Expr(acc) :: exprs).reverse match
                            case Nil => Expr("")
                            case exprs =>
                                exprs.filter {
                                    case Expr("") => false
                                    case _        => true
                                }.reduce((a, b) => '{ $a + $b })
                    case Expr(s: String) :: next =>
                        loop(next, acc + s, exprs)
                    case head :: next =>
                        loop(next, "", head :: Expr(acc) :: exprs)
            loop(l, "", Nil)
        end concat
    end internal
end Tag
