package com.etiya.replaylab.service;

import com.etiya.replaylab.model.ApplicationDbEvidenceQueryTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ApplicationDbEvidenceQueryRegistry {

    public static final String USER_PREFERRED_PROVINCE =
            "USER_PREFERRED_PROVINCE";
    public static final String USER_REGION_STATE = "USER_REGION_STATE";
    public static final String BILLING_ACCOUNT_REGION =
            "BILLING_ACCOUNT_REGION";
    public static final String TAX_INFO_STATE = "TAX_INFO_STATE";
    public static final String TIMEZONE_STATE = "TIMEZONE_STATE";
    public static final String CUSTOMER_ADDRESS_REGION =
            "CUSTOMER_ADDRESS_REGION";
    public static final String ORDER_CONTEXT = "ORDER_CONTEXT";
    public static final String UNKNOWN = "UNKNOWN";

    private final Map<String, ApplicationDbEvidenceQueryTemplate> templates;

    public ApplicationDbEvidenceQueryRegistry(SqlReadOnlyGuard guard) {
        Map<String, ApplicationDbEvidenceQueryTemplate> values =
                new LinkedHashMap<>();
        register(values, guard, new ApplicationDbEvidenceQueryTemplate(
                USER_PREFERRED_PROVINCE,
                "User preferred province",
                "Read preferred province stored for the affected application user.",
                List.of("userId"),
                List.of("apl_user", "party"),
                List.of("user_id", "party_id", "pref_prvnc_id"),
                "select u.user_id, u.party_id, p.pref_prvnc_id from apl_user u join party p on p.party_id = u.party_id where u.user_id = :userId",
                List.of("user_id", "party_id"),
                true
        ));
        register(values, guard, new ApplicationDbEvidenceQueryTemplate(
                USER_REGION_STATE,
                "User region state",
                "Read state/province details used by the user region update flow.",
                List.of("stateCode"),
                List.of("state"),
                List.of("state_id", "state_code", "is_actv"),
                "select state_id, state_code, is_actv from state where state_code = :stateCode",
                List.of(),
                true
        ));
        register(values, guard, new ApplicationDbEvidenceQueryTemplate(
                BILLING_ACCOUNT_REGION,
                "Billing account region",
                "Read billing account region/province linkage for the affected account.",
                List.of("billingAccountId"),
                List.of("billing_account"),
                List.of("billing_account_id", "party_id", "region_id"),
                "select billing_account_id, party_id, region_id from billing_account where billing_account_id = :billingAccountId",
                List.of("billing_account_id", "party_id"),
                true
        ));
        register(values, guard, new ApplicationDbEvidenceQueryTemplate(
                TAX_INFO_STATE,
                "Tax info state",
                "Read tax info state/province values relevant to billing account consistency.",
                List.of("partyId"),
                List.of("tax_info"),
                List.of("party_id", "tax_region", "tax_state_code"),
                "select party_id, tax_region, tax_state_code from tax_info where party_id = :partyId",
                List.of("party_id"),
                true
        ));
        register(values, guard, new ApplicationDbEvidenceQueryTemplate(
                TIMEZONE_STATE,
                "Timezone state",
                "Read timezone values linked to the customer or party context.",
                List.of("partyId"),
                List.of("party"),
                List.of("party_id", "time_zone"),
                "select party_id, time_zone from party where party_id = :partyId",
                List.of("party_id"),
                true
        ));
        register(values, guard, new ApplicationDbEvidenceQueryTemplate(
                CUSTOMER_ADDRESS_REGION,
                "Customer address region",
                "Read address region/province for customer consistency checks.",
                List.of("customerId"),
                List.of("customer_address"),
                List.of("customer_id", "state_code", "province_code"),
                "select customer_id, state_code, province_code from customer_address where customer_id = :customerId",
                List.of("customer_id"),
                true
        ));
        register(values, guard, new ApplicationDbEvidenceQueryTemplate(
                ORDER_CONTEXT,
                "Order context",
                "Read order context associated with the incident when order id exists.",
                List.of("orderId"),
                List.of("cust_ord"),
                List.of("cust_ord_id", "party_id", "created_date"),
                "select cust_ord_id, party_id, created_date from cust_ord where cust_ord_id = :orderId",
                List.of("cust_ord_id", "party_id"),
                true
        ));
        register(values, guard, new ApplicationDbEvidenceQueryTemplate(
                UNKNOWN,
                "Unknown DB evidence",
                "Placeholder template when no deterministic DB evidence template matches.",
                List.of(),
                List.of(),
                List.of(),
                "select 1 as replaylab_unknown_template",
                List.of(),
                true
        ));
        templates = Map.copyOf(values);
    }

    public List<ApplicationDbEvidenceQueryTemplate> templates() {
        return List.copyOf(templates.values());
    }

    public Optional<ApplicationDbEvidenceQueryTemplate> findById(
            String templateId
    ) {
        return Optional.ofNullable(templates.get(templateId));
    }

    public List<ApplicationDbEvidenceQueryTemplate> relevantTemplates(
            String signals
    ) {
        String value = signals == null ? "" : signals.toLowerCase();
        Map<String, ApplicationDbEvidenceQueryTemplate> selected =
                new LinkedHashMap<>();
        if (containsAny(value, "preferredprovince", "preferred province")) {
            add(selected, USER_PREFERRED_PROVINCE);
        }
        if (containsAny(value, "region", "province", "state")) {
            add(selected, USER_REGION_STATE);
            add(selected, CUSTOMER_ADDRESS_REGION);
        }
        if (containsAny(value, "taxinfo", "tax_info", "tax info", "tax")) {
            add(selected, TAX_INFO_STATE);
        }
        if (containsAny(value, "timezone", "time_zone", "time zone")) {
            add(selected, TIMEZONE_STATE);
        }
        if (containsAny(value, "billingaccount", "billing account", "billing")) {
            add(selected, BILLING_ACCOUNT_REGION);
        }
        if (containsAny(value, "orderid", "order id", "cust_ord")) {
            add(selected, ORDER_CONTEXT);
        }
        if (selected.isEmpty()) {
            add(selected, UNKNOWN);
        }
        return List.copyOf(selected.values());
    }

    private void register(
            Map<String, ApplicationDbEvidenceQueryTemplate> values,
            SqlReadOnlyGuard guard,
            ApplicationDbEvidenceQueryTemplate template
    ) {
        guard.validateSelectOnly(template.sqlPreview());
        values.put(template.templateId(), template);
    }

    private void add(
            Map<String, ApplicationDbEvidenceQueryTemplate> selected,
            String templateId
    ) {
        ApplicationDbEvidenceQueryTemplate template = templates.get(templateId);
        if (template != null) {
            selected.put(templateId, template);
        }
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
