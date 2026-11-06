package com.bi3bju.rattlegram_plus;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rig model picker that finds models by any part of their name ("857", "yaesu 857", "1022"). */
final class RigModelAdapter extends ArrayAdapter<HamlibRig.Model> {
    private final List<HamlibRig.Model> all;

    RigModelAdapter(Context context, List<HamlibRig.Model> models) {
        super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(models));
        all = models;
    }

    /** Whether every word typed appears somewhere in the model's label. */
    static boolean matches(HamlibRig.Model model, String query) {
        String label = model.toString().toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+"))
            if (!label.contains(word))
                return false;
        return true;
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                List<HamlibRig.Model> found = new ArrayList<>();
                String query = constraint == null ? "" : constraint.toString();
                for (HamlibRig.Model m : all)
                    if (query.trim().isEmpty() || matches(m, query))
                        found.add(m);
                FilterResults results = new FilterResults();
                results.values = found;
                results.count = found.size();
                return results;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void publishResults(CharSequence constraint, FilterResults results) {
                clear();
                addAll((List<HamlibRig.Model>) results.values);
                notifyDataSetChanged();
            }

            @Override
            public CharSequence convertResultToString(Object result) {
                return result.toString();
            }
        };
    }
}
