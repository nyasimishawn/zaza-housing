package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.*;
import com.zaza.housing.exception.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DiscoveryQueryService {
  public DiscoveryQuery normalize(DiscoveryQuery r, boolean saved) {
    if (r == null) throw bad("Choose discovery filters.");
    String kind = r.kind() == null ? "PROPERTY" : r.kind().toUpperCase(Locale.ROOT);
    if (!Set.of("PROPERTY", "PROVIDER").contains(kind))
      throw bad("Choose property or provider discovery.");
    String q = clean(r.q()),
        city = clean(r.city()),
        country = upper(r.countryCode()),
        currency = upper(r.currency()),
        type = upper(r.type()),
        category = upper(r.category()),
        sort = upper(r.sort());
    if (q != null && (q.length() < 2 || q.length() > 200) || city != null && city.length() > 100)
      throw bad("Use a search of 2–200 characters and a city of at most 100 characters.");
    if (country != null && !Arrays.asList(Locale.getISOCountries()).contains(country))
      throw bad("Use a valid country code.");
    if (currency != null)
      try {
        Currency.getInstance(currency);
      } catch (IllegalArgumentException e) {
        throw bad("Use a valid currency.");
      }
    if (type != null)
      try {
        PropertyType.valueOf(type);
      } catch (IllegalArgumentException e) {
        throw bad("Choose a supported property type.");
      }
    if (category != null)
      try {
        ServiceCategory.valueOf(category);
      } catch (IllegalArgumentException e) {
        throw bad("Choose a supported service category.");
      }
    if (sort == null) sort = kind.equals("PROPERTY") ? "NEWEST" : "NAME";
    if (!Set.of("NEWEST", "NAME", "PRICE_ASC", "PRICE_DESC", "DISTANCE").contains(sort))
      throw bad("Choose a supported sort.");
    if (kind.equals("PROVIDER")
        && (type != null
            || currency != null
            || r.minRent() != null
            || r.maxRent() != null
            || r.minBedrooms() != null
            || r.maxBedrooms() != null
            || sort.startsWith("PRICE")))
      throw bad("Rental price, type and bedroom filters apply to properties.");
    if (kind.equals("PROPERTY") && (category != null || Boolean.TRUE.equals(r.verifiedOnly())))
      throw bad("Category and verified-business filters apply to providers.");
    if ((r.minRent() != null || r.maxRent() != null || sort.startsWith("PRICE"))
        && currency == null) throw bad("Choose a currency before filtering or sorting rent.");
    if (r.minRent() != null && r.maxRent() != null && r.minRent().compareTo(r.maxRent()) > 0
        || r.minBedrooms() != null && r.maxBedrooms() != null && r.minBedrooms() > r.maxBedrooms())
      throw bad("The minimum cannot exceed the maximum.");
    boolean centre = r.latitude() != null || r.longitude() != null,
        box = r.north() != null || r.south() != null || r.east() != null || r.west() != null;
    if (centre && (r.latitude() == null || r.longitude() == null)
        || r.radiusKm() != null && !centre)
      throw bad("Supply both centre coordinates for distance search.");
    check(r.latitude(), 85.05112878);
    check(r.longitude(), 180);
    check(r.north(), 85.05112878);
    check(r.south(), 85.05112878);
    check(r.east(), 180);
    check(r.west(), 180);
    if (r.radiusKm() != null
        && (!Double.isFinite(r.radiusKm()) || r.radiusKm() <= 0 || r.radiusKm() > 500))
      throw bad("Search within 0–500 km.");
    if (box
        && (r.north() == null
            || r.south() == null
            || r.east() == null
            || r.west() == null
            || r.north() <= r.south())) throw bad("Supply a valid map viewport.");
    if (sort.equals("DISTANCE") && !centre) throw bad("Choose a centre before sorting distance.");
    if (kind.equals("PROVIDER") && (centre || box) && country == null)
      throw bad("Choose a country for geographic provider coverage search.");
    return new DiscoveryQuery(
        kind,
        q,
        city,
        country,
        type,
        currency,
        r.minRent(),
        r.maxRent(),
        r.minBedrooms(),
        r.maxBedrooms(),
        category,
        Boolean.TRUE.equals(r.verifiedOnly()),
        r.latitude(),
        r.longitude(),
        r.radiusKm(),
        r.north(),
        r.south(),
        r.east(),
        r.west(),
        sort,
        saved ? 20 : r.limit() == null ? 20 : r.limit(),
        saved ? 0 : r.offset() == null ? 0 : r.offset());
  }

  public static String clean(String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }

  private String upper(String v) {
    v = clean(v);
    return v == null ? null : v.toUpperCase(Locale.ROOT);
  }

  private void check(Double value, double max) {
    if (value != null && (!Double.isFinite(value) || Math.abs(value) > max))
      throw bad("Use valid map coordinates.");
  }

  public static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }
}
