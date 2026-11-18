package pk.techtonic.jarvis.ui

import android.app.AlertDialog
import android.app.Fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import pk.techtonic.jarvis.R
import pk.techtonic.jarvis.data.JarvisDb
import pk.techtonic.jarvis.util.Calculator
import pk.techtonic.jarvis.util.Units
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Hisab Tools tab (W3): calculator, milk-sale hisab (exact integer-paisa
 * math from HTML v1.0.0), and a unit converter. All numbers are shown in
 * the Latin style — never Jameel for digits.
 */
class HisabFragment : Fragment() {

    private lateinit var db: JarvisDb

    private lateinit var calcInput: EditText
    private lateinit var calcResult: TextView

    private lateinit var milkLitres: EditText
    private lateinit var milkRate: EditText
    private lateinit var milkResult: TextView
    private lateinit var milkList: LinearLayout
    private lateinit var milkTotal: TextView

    private lateinit var convValue: EditText
    private lateinit var convFrom: Spinner
    private lateinit var convTo: Spinner
    private lateinit var convResult: TextView

    private val unitCodes = arrayOf("kg", "maund", "metre", "yard", "gaz")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_hisab, container, false)
        db = (activity as MainActivity).db

        // ---- Calculator ----
        calcInput = view.findViewById(R.id.calc_input)
        calcResult = view.findViewById(R.id.calc_result)
        view.findViewById<Button>(R.id.calc_eval).setOnClickListener { evaluateCalc() }

        // ---- Milk hisab ----
        milkLitres = view.findViewById(R.id.milk_litres)
        milkRate = view.findViewById(R.id.milk_rate)
        milkResult = view.findViewById(R.id.milk_result)
        milkList = view.findViewById(R.id.milk_list)
        milkTotal = view.findViewById(R.id.milk_total)
        view.findViewById<Button>(R.id.milk_calc).setOnClickListener { calculateMilk() }
        view.findViewById<Button>(R.id.milk_clear).setOnClickListener { clearMilk() }
        renderMilk()

        // ---- Unit converter ----
        convValue = view.findViewById(R.id.conv_value)
        convFrom = view.findViewById(R.id.conv_from)
        convTo = view.findViewById(R.id.conv_to)
        convResult = view.findViewById(R.id.conv_result)
        val unitLabels = unitCodes.map { code -> getString(unitLabelRes(code)) }
        val spinnerAdapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            unitLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        convFrom.adapter = spinnerAdapter
        convTo.adapter = spinnerAdapter
        convTo.setSelection(1) // maund: a useful default pair (kg -> maund)
        view.findViewById<Button>(R.id.conv_calc).setOnClickListener { convertUnits() }

        return view
    }

    // ---------- Calculator ----------

    private fun evaluateCalc() {
        val expr = calcInput.text.toString().trim()
        if (expr.isEmpty()) {
            calcResult.setText(R.string.hisab_calc_error)
            Toast.makeText(activity, R.string.hisab_calc_error, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            calcResult.text = Calculator.evaluate(expr)
        } catch (e: Calculator.CalculatorException) {
            val res = if (e.code == "divzero") {
                R.string.hisab_calc_div_zero
            } else {
                R.string.hisab_calc_error
            }
            calcResult.setText(res)
            Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Milk hisab ----------

    private fun calculateMilk() {
        val litres = milkLitres.text.toString().trim().toDoubleOrNull()
        val rate = milkRate.text.toString().trim().toDoubleOrNull()
        if (litres == null || rate == null || litres <= 0.0 || rate <= 0.0 ||
            !litres.isFinite() || !rate.isFinite()
        ) {
            milkResult.setText(R.string.hisab_milk_invalid)
            Toast.makeText(activity, R.string.hisab_milk_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        // EXACT HTML math: litres rounded to 2 decimals first, then the
        // amount rounded to 2 decimals. Integer paisa stored in the DB.
        var amount = Math.round(litres * 100) * rate / 100.0
        amount = Math.round(amount * 100) / 100.0
        val litresMilli = Math.round(litres * 1000)
        val ratePaisa = Math.round(rate * 100)
        val totalPaisa = Math.round(amount * 100)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Calendar.getInstance().time)
        db.addMilk(today, litresMilli, ratePaisa, totalPaisa)
        milkResult.text = fmt3(litres) + " " + getString(R.string.hisab_milk_litres) +
            " × " + fmt3(rate) + " = " + money(amount) +
            " " + getString(R.string.hisab_currency)
        renderMilk()
        Toast.makeText(activity, R.string.hisab_milk_saved_msg, Toast.LENGTH_SHORT).show()
    }

    private fun renderMilk() {
        val entries = try {
            db.allMilk()
        } catch (ignored: Exception) {
            emptyList()
        }
        milkList.removeAllViews()
        if (entries.isEmpty()) {
            milkList.addView(
                TextView(activity).apply {
                    setText(R.string.hisab_milk_empty)
                    setTextAppearance(android.R.style.TextAppearance_Medium)
                }
            )
            milkTotal.text = ""
            return
        }
        var totalPaisa = 0L
        for (entry in entries) {
            totalPaisa += entry.totalPaisa
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val line1 = TextView(activity).apply {
                text = entry.date
                setTextAppearance(android.R.style.TextAppearance_Small)
            }
            val line2 = TextView(activity).apply {
                text = fmt3(entry.litresMilli / 1000.0) + " " +
                    getString(R.string.hisab_milk_litres) + " × " +
                    money(entry.ratePaisa / 100.0) + " = " +
                    money(entry.totalPaisa / 100.0) + " " +
                    getString(R.string.hisab_currency)
                setTextAppearance(android.R.style.TextAppearance_Medium)
            }
            row.addView(line1)
            row.addView(line2)
            milkList.addView(row)
        }
        milkTotal.text = getString(R.string.hisab_milk_total) + ": " +
            money(totalPaisa / 100.0) + " " + getString(R.string.hisab_currency)
    }

    private fun clearMilk() {
        if (db.allMilk().isEmpty()) return
        AlertDialog.Builder(activity)
            .setMessage(R.string.hisab_milk_clear_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                db.clearMilk()
                renderMilk()
                Toast.makeText(activity, R.string.hisab_milk_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ---------- Unit converter ----------

    private fun convertUnits() {
        val raw = convValue.text.toString().trim()
        if (raw.isEmpty()) {
            convResult.setText(R.string.hisab_conv_result_empty)
            return
        }
        val value = raw.toDoubleOrNull()
        if (value == null || !value.isFinite()) {
            convResult.setText(R.string.hisab_conv_invalid)
            Toast.makeText(activity, R.string.hisab_conv_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val from = unitCodes[convFrom.selectedItemPosition]
        val to = unitCodes[convTo.selectedItemPosition]
        val out = Units.convert(value, from, to)
        if (out == null) {
            convResult.setText(R.string.hisab_conv_mismatch)
            Toast.makeText(activity, R.string.hisab_conv_mismatch, Toast.LENGTH_SHORT).show()
            return
        }
        convResult.text = fmt3(value) + " " + getString(unitLabelRes(from)) +
            " = " + fmt3(out) + " " + getString(unitLabelRes(to))
    }

    private fun unitLabelRes(code: String): Int = when (code) {
        "kg" -> R.string.hisab_unit_kg
        "maund" -> R.string.hisab_unit_maund
        "metre" -> R.string.hisab_unit_metre
        "yard" -> R.string.hisab_unit_yard
        "gaz" -> R.string.hisab_unit_gaz
        else -> R.string.hisab_unit_kg
    }

    /** Money: always exactly 2 decimals. */
    private fun money(n: Double): String = String.format(Locale.US, "%.2f", n)

    /** Max 3 decimals, trailing zeros trimmed (HTML fmt3). */
    private fun fmt3(n: Double): String {
        var s = String.format(Locale.US, "%.3f", n)
        s = s.trimEnd('0').trimEnd('.')
        if (s.isEmpty() || s == "-0") s = "0"
        return s
    }
}
