package rpi.learner

import java.util.concurrent.atomic.AtomicInteger

import rpi.Config
import rpi.inference._
import rpi.util.{Collections, Expressions}
import viper.silver.{ast => sil}

class GuardEncoder(learner: Learner, templates: Map[String, Template]) {
  /**
    * Type shortcut for an effective guard.
    *
    * The outer sequence represents a choice of exactly one of the options. The inner sequence represents a disjunction
    * of guards. The guards are represented by their id and which atomic predicates of the context correspond to the
    * atomic predicates of the guard.
    */
  private type Guard = Seq[Seq[(Int, Seq[sil.Exp])]]

  private val guards: Map[String, Map[sil.LocationAccess, Guard]] = {
    // compute effective guards.
    val result = templates
      .map { case (name, template) =>
        // TODO: Depth depending on length of longest access path.
        name -> collectGuards(template, View.empty, depth = 3)
      }

    // debug printing
    if (Config.debugPrintGuards) result
      .foreach { case (name, map) => map
        .foreach { case (location, guard) =>
          val label = s"$location@$name"
          val string = guard
            .map { choice =>
              choice
                .map { case (id, atoms) =>
                  s"phi_$id${atoms.mkString("[", ",", "]")}"
                }
                .mkString(" && ")
            }
            .mkString("{", ", ", "}")
          println(s"$label: $string")
        }
      }

    // return effective guards
    result
  }

  private val unique = new AtomicInteger

  /**
    * Returns the encoding of the given examples.
    *
    * @param examples The examples to encode.
    * @return The constraints representing the encodings of the examples.
    */
  def encodeExamples(examples: Seq[Example]): Seq[sil.Exp] =
    examples.flatMap { example => encodeExample(example) }

  /**
    * Returns the encoding of the given example.
    *
    * @param example The example to encode.
    * @return The constraints representing the encoding of the example.
    */
  def encodeExample(example: Example): Seq[sil.Exp] =
    example match {
      case Positive(record) =>
        val (encoding, global) = encodeRecord(record)
        encoding +: global
      case Negative(record) =>
        val (encoding, global) = encodeRecord(record)
        sil.Not(encoding)() +: global
      case Implication(left, right) =>
        val (encoding1, global1) = encodeRecord(left)
        val (encoding2, global2) = encodeRecord(right)
        sil.Implies(encoding1, encoding2)() +: (global1 ++ global2)
    }

  /**
    * Returns the encoding of the given record.
    *
    * @param record The record to encode.
    * @return The encoding plus a list of global constraints.
    */
  def encodeRecord(record: Record): (sil.Exp, Seq[sil.Exp]) = {
    val name = record.specification.name
    val localGuards = guards.getOrElse(name, Map.empty)

    // compute encodings
    val encodings = record
      .locations
      .flatMap { location =>
        // get guard for location
        val locationGuard = localGuards.getOrElse(location, Seq.empty)
        // choices for this location
        locationGuard.map { sequence =>
          val conjuncts = sequence.map { case (id, atoms) =>
            val values = record.state.getValues(atoms)
            encodeState(id, values)
          }
          Expressions.bigAnd(conjuncts)
        }
      }

    // assign encodings to auxiliary variables
    val (choices, constraints) = {
      val empty = Seq.empty[sil.Exp]
      encodings.foldLeft((empty, empty)) {
        case ((variables, equalities), choice) =>
          val variable = sil.LocalVar(s"t_${unique.getAndIncrement()}", sil.Bool)()
          val equality = sil.EqCmp(variable, choice)()
          (variables :+ variable, equalities :+ equality)
      }
    }

    // compute encodings for picking at least one and at most one choice
    val atLeast = Expressions.bigOr(choices)
    val atMost = {
      val pairs = Collections
        .pairs(choices)
        .map { case (first, second) => sil.Not(sil.And(first, second)())() }
      Expressions.bigAnd(pairs)
    }

    // return encoding and global constraints
    (atLeast, atMost +: constraints)
  }

  /**
    * Computes the encoding of an abstract state defined by the given values for the guard with the given id.
    *
    * TODO: Approximation.
    *
    * @param id     The id of the guard.
    * @param values The values defining the state.
    * @return The encoding.
    */
  private def encodeState(id: Int, values: Seq[Boolean]): sil.Exp = {
    // encode clauses
    val clauses = for (j <- 0 until Config.maxClauses) yield {
      val clauseActivation = sil.LocalVar(s"x_${id}_$j", sil.Bool)()
      val clauseEncoding = {
        // encode literals
        val literals = values
          .zipWithIndex
          .map { case (value, i) =>
            val literalActivation = sil.LocalVar(s"y_${id}_${i}_$j", sil.Bool)()
            val literalEncoding = {
              val sign = sil.LocalVar(s"s_${id}_${i}_$j", sil.Bool)()
              if (value) sign else sil.Not(sign)()
            }
            sil.Implies(literalActivation, literalEncoding)()
          }
        // conjoin all literals
        Expressions.bigAnd(literals)
      }
      sil.And(clauseActivation, clauseEncoding)()
    }
    // return disjunction of clauses
    Expressions.bigOr(clauses)
  }


  /**
    * Collects the effective guards for the given template up to the given depth.
    *
    * @param template The template for which to collect the effective guards.
    * @param view     The view used to adapt expressions to the current context.
    * @param depth    The depth.
    * @return The collected effective guards.
    */
  private def collectGuards(template: Template, view: View, depth: Int): Map[sil.LocationAccess, Guard] = {
    val empty = Map.empty[sil.LocationAccess, Guard]
    if (depth == 0) empty
    else {
      // get and adapt atoms
      val atoms = template
        .atoms
        .map { atom => view.adapt(atom) }
      // process accesses
      template
        .accesses
        .foldLeft(empty) {
          case (result, Guarded(id, access)) => access match {
            case sil.FieldAccess(receiver, field) =>
              // update guard of field access
              val adapted = sil.FieldAccess(view.adapt(receiver), field)()
              val guard = result.getOrElse(adapted, Seq.empty) :+ Seq((id, atoms))
              result.updated(adapted, guard)
            case sil.PredicateAccess(arguments, name) =>
              // update guard of predicate access
              val adaptedArguments = arguments.map { argument => view.adapt(argument) }
              val adapted = sil.PredicateAccess(adaptedArguments, name)()
              val guard = result.getOrElse(adapted, Seq.empty) :+ Seq((id, atoms))
              val updated = result.updated(adapted, guard)
              // process nested accesses
              val innerTemplate = templates(name)
              val innerView = View.create(innerTemplate, adaptedArguments)
              collectGuards(innerTemplate, innerView, depth - 1).foldLeft(updated) {
                case (innerResult, (innerAccess, innerGuard)) =>
                  val mappedGuard = innerGuard.map { choice => choice :+ (id, atoms) }
                  val updatedGuard = innerResult.getOrElse(innerAccess, Seq.empty) ++ mappedGuard
                  innerResult.updated(innerAccess, updatedGuard)
              }
          }
        }
    }
  }

  object View {
    def empty: View =
      View(Map.empty)

    def create(template: Template, arguments: Seq[sil.Exp]): View = {
      val names = template
        .specification
        .parameters
        .map { parameter => parameter.name }
      View(names.zip(arguments).toMap)
    }
  }

  case class View(map: Map[String, sil.Exp]) {
    def isEmpty: Boolean = map.isEmpty

    def adapt(expression: sil.Exp): sil.Exp =
      if (isEmpty) expression
      else expression.transform {
        case sil.LocalVar(name, _) => map(name)
      }
  }

}
